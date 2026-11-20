package es.brasatech.fastbite.application.verifactu;

import es.brasatech.fastbite.application.digitalsign.CallOptions;
import es.brasatech.fastbite.application.digitalsign.CertificateNifExtractor;
import es.brasatech.fastbite.application.digitalsign.CertificateNifExtractor.VerificationResult;
import es.brasatech.fastbite.application.digitalsign.CreateRequestCommand;
import es.brasatech.fastbite.application.digitalsign.CreateRequestCommand.SignerInput;
import es.brasatech.fastbite.application.digitalsign.CreateRequestResult;
import es.brasatech.fastbite.application.digitalsign.DigitalSignClient;
import es.brasatech.fastbite.application.digitalsign.DigitalSignException;
import es.brasatech.fastbite.application.digitalsign.DigitalSignProperties;
import es.brasatech.fastbite.application.digitalsign.DigitalSignRequest;
import es.brasatech.fastbite.application.digitalsign.DigitalSignRequest.RequestSigner;
import es.brasatech.fastbite.application.digitalsign.DigitalSignTemplate;
import es.brasatech.fastbite.application.digitalsign.DigitalSignTemplate.TemplateField;
import es.brasatech.fastbite.application.digitalsign.DigitalSignTemplate.TemplateSigner;
import es.brasatech.fastbite.application.digitalsign.SigningUrlResponse;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationDocumentStorage.StoredDocument;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationStore.UpdateResult;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.fiscal.SpanishTaxId;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorization;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorizationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Coordinates VERI*FACTU authorisation signing, state synchronisation, certificate NIF verification,
 * private PDF downloads, webhook deduplication, and background reconciliation.
 */
@Service
public class VerifactuAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(VerifactuAuthorizationService.class);

    /** Total synchronous budget for webhook handling on Cloud Run (~8 s < Digital Sign's 10 s limit). */
    public static final Duration WEBHOOK_BUDGET = Duration.ofSeconds(8);
    /** Tight budget for automatic refresh on /owner/console page load so the page stays fast. */
    public static final Duration PAGE_LOAD_REFRESH_BUDGET = Duration.ofSeconds(3);
    /** Age without a sync after which an IN_PROGRESS request is polled by reconciliation. */
    public static final Duration STALE_IN_PROGRESS_AGE = Duration.ofHours(1);
    /** Retention period for webhook deduplication records. */
    public static final Duration WEBHOOK_EVENT_RETENTION = Duration.ofDays(30);
    /** Per-item budget during background reconciliation so one slow request cannot starve others. */
    public static final Duration RECONCILE_ITEM_BUDGET = Duration.ofSeconds(15);
    public static final int DEFAULT_RECONCILE_BATCH_SIZE = 50;
    /** Maximum repeated 404/410 or documents-not-ready sync failures before moving to terminal on-hold. */
    public static final int MAX_SYNC_FAILURE_ATTEMPTS = 3;
    /** Maximum age before repeated 404/410 or documents-not-ready sync failures move to terminal on-hold. */
    public static final Duration MAX_SYNC_FAILURE_AGE = Duration.ofDays(7);
    /** Maximum number of CAS retry passes when a concurrent writer advances an active row. */
    private static final int MAX_CAS_RETRIES = 1;
    /** Default signing request expiry window in days. */
    private static final int SIGNING_EXPIRY_DAYS = 30;

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[+\\d][\\d\\s\\-()]{5,24}$");
    private static final Pattern IBAN_PATTERN = Pattern.compile("^[A-Z]{2}\\d{2}[A-Z0-9]{10,30}$");

    public enum WebhookOutcome {
        PROCESSED,
        DUPLICATE
    }

    public record ReconcileSummary(int checked, int updated, int webhookEventsPurged) {
        public static final ReconcileSummary EMPTY = new ReconcileSummary(0, 0, 0);
    }

    public record StartSigningResult(
            Outcome outcome,
            String signingUrl,
            VerifactuAuthorization authorization,
            String reason) {

        public enum Outcome {
            SIGNING_URL,
            ALREADY_VALID,
            SIGNING_UNAVAILABLE,
            FISCAL_INCOMPLETE
        }

        public static StartSigningResult signingUrl(String url, VerifactuAuthorization auth) {
            return new StartSigningResult(Outcome.SIGNING_URL, url, auth, null);
        }

        public static StartSigningResult alreadyValid(VerifactuAuthorization auth) {
            return new StartSigningResult(Outcome.ALREADY_VALID, null, auth, null);
        }

        public static StartSigningResult unavailable(String reason) {
            return new StartSigningResult(Outcome.SIGNING_UNAVAILABLE, null, null, reason);
        }

        public static StartSigningResult fiscalIncomplete() {
            return new StartSigningResult(Outcome.FISCAL_INCOMPLETE, null, null, "FISCAL_INCOMPLETE");
        }

        @Override
        public String toString() {
            return "StartSigningResult{"
                    + "outcome=" + outcome
                    + ", signingUrl=" + (signingUrl != null && !signingUrl.isBlank() ? "[REDACTED]" : "null")
                    + ", authorizationId=" + (authorization != null ? authorization.id() : "null")
                    + ", reason='" + reason + '\''
                    + '}';
        }
    }

    private final VerifactuAuthorizationStore store;
    private final VerifactuAuthorizationDocumentStorage documentStorage;
    private final DigitalSignClient digitalSignClient;
    private final DigitalSignProperties digitalSignProperties;
    private final Clock clock;
    private final boolean collaborationActive;
    private final String brasatechLegalName;
    private final String brasatechTaxId;
    private final String brasatechAddress;

    public VerifactuAuthorizationService(
            VerifactuAuthorizationStore store,
            VerifactuAuthorizationDocumentStorage documentStorage,
            DigitalSignClient digitalSignClient,
            DigitalSignProperties digitalSignProperties,
            Clock clock) {
        this(store, documentStorage, digitalSignClient, digitalSignProperties, clock, false,
                "BRASATECH DIGITAL SOLUTIONS, S.L.U.", "B09767898",
                "Calle Ganaderos, 24, bajo, 03130 Santa Pola (Alicante), España");
    }

    @Autowired
    public VerifactuAuthorizationService(
            VerifactuAuthorizationStore store,
            VerifactuAuthorizationDocumentStorage documentStorage,
            DigitalSignClient digitalSignClient,
            DigitalSignProperties digitalSignProperties,
            Clock clock,
            @Value("${fastbite.verifactu.collaboration-active:false}") boolean collaborationActive,
            @Value("${fastbite.legal.company-name:BRASATECH DIGITAL SOLUTIONS, S.L.U.}") String brasatechLegalName,
            @Value("${fastbite.legal.tax-id:B09767898}") String brasatechTaxId,
            @Value("${fastbite.legal.address:Calle Ganaderos, 24, bajo, 03130 Santa Pola (Alicante), España}") String brasatechAddress) {
        this.store = Objects.requireNonNull(store, "store");
        this.documentStorage = Objects.requireNonNull(documentStorage, "documentStorage");
        this.digitalSignClient = Objects.requireNonNull(digitalSignClient, "digitalSignClient");
        this.digitalSignProperties = Objects.requireNonNull(digitalSignProperties, "digitalSignProperties");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.collaborationActive = collaborationActive;
        this.brasatechLegalName = brasatechLegalName == null ? "" : brasatechLegalName.strip();
        this.brasatechTaxId = brasatechTaxId == null ? "" : brasatechTaxId.strip();
        this.brasatechAddress = brasatechAddress == null ? "" : brasatechAddress.strip();
    }

    /** True only when Brasatech has enabled VERI*FACTU social collaboration ({@code VERIFACTU_COLLABORATION_ACTIVE=true}). */
    public boolean isCollaborationActive() {
        return collaborationActive;
    }

    /** True when Digital Sign API token and VERI*FACTU template ID are configured. */
    public boolean isSigningConfigured() {
        return digitalSignClient.isConfigured() && digitalSignProperties.isVerifactuConfigured();
    }

    /** True only when the NIF currently has a VALID authorisation with both PDFs stored. */
    public boolean hasValidAuthorization(String nif) {
        String normalized = normalizeNif(nif);
        return normalized != null && store.hasValidAuthorization(normalized);
    }

    /** True when a production location with this NIF satisfies both VERI*FACTU authorisation and the platform switch. */
    public boolean isAuthorizedToOpen(String nif) {
        return collaborationActive && hasValidAuthorization(nif);
    }

    /** True when the NIF had a valid authorisation with both PDFs stored at {@code issuedAt}. */
    public boolean wasAuthorizedAt(String nif, Instant issuedAt) {
        String normalized = normalizeNif(nif);
        return normalized != null && issuedAt != null && store.wasAuthorizedAt(normalized, issuedAt);
    }

    /** Returns the current authorisation for a NIF (preferring VALID, then active, then latest). */
    public Optional<VerifactuAuthorization> findCurrentForNif(String nif) {
        String normalized = normalizeNif(nif);
        if (normalized == null) {
            return Optional.empty();
        }
        return store.findValidByNif(normalized)
                .or(() -> store.findActiveByNif(normalized))
                .or(() -> store.findLatestByNif(normalized));
    }

    /**
     * User-driven refresh for the "Comprobar ahora" button: if the NIF has an {@code IN_PROGRESS}
     * or {@code DOWNLOAD_FAILED} authorisation, refreshes it with the full synchronous budget without
     * consuming bounded failure attempts.
     */
    public Optional<VerifactuAuthorization> refreshForNif(String nif) {
        return refreshForNifWithBudget(nif, WEBHOOK_BUDGET);
    }

    /**
     * Fast automatic refresh on {@code /owner/console} page load (~3 s budget) while a production
     * location is not yet open. Never consumes bounded failure attempts.
     */
    public Optional<VerifactuAuthorization> refreshForNifOnPageLoad(String nif) {
        return refreshForNifWithBudget(nif, PAGE_LOAD_REFRESH_BUDGET);
    }

    private Optional<VerifactuAuthorization> refreshForNifWithBudget(String nif, Duration budget) {
        String normalized = normalizeNif(nif);
        if (normalized == null) {
            return Optional.empty();
        }
        Optional<VerifactuAuthorization> active = store.findActiveByNif(normalized);
        if (active.isPresent() && active.get().digitalSignRequestId() != null
                && !active.get().digitalSignRequestId().isBlank()) {
            return syncRequest(active.get().digitalSignRequestId(), clock.instant().plus(budget), false, false);
        }
        return findCurrentForNif(normalized);
    }

    /**
     * Starts or resumes a VERI*FACTU Anexo I signing request in Digital Sign for the location's NIF.
     *
     * <p>The returned {@code signingUrl} is a secret and is never logged or persisted.
     */
    public StartSigningResult startSigning(
            FiscalProfile fiscalProfile,
            String ownerUsername,
            String ownerName,
            String ownerEmail,
            String language) {
        if (fiscalProfile == null || !fiscalProfile.complete()) {
            return StartSigningResult.fiscalIncomplete();
        }
        Optional<SpanishTaxId> parsedTaxId = SpanishTaxId.parse(fiscalProfile.taxId());
        if (parsedTaxId.isEmpty()) {
            return StartSigningResult.fiscalIncomplete();
        }
        String nif = parsedTaxId.get().value();
        String legalName = truncate(fiscalProfile.legalName().strip(), 120);

        Optional<VerifactuAuthorization> valid = store.findValidByNif(nif);
        if (valid.isPresent() && valid.get().isCurrentlyValid()) {
            return StartSigningResult.alreadyValid(valid.get());
        }

        if (!isSigningConfigured()) {
            log.warn("VERI*FACTU signing unavailable: Digital Sign API token or templateId is not configured");
            return StartSigningResult.unavailable("SIGNING_UNAVAILABLE");
        }

        String configuredRole = configuredSignerRole();
        if (configuredRole == null) {
            log.warn("VERI*FACTU signing unavailable: fastbite.digitalsign.verifactu.signer-role is not configured");
            return StartSigningResult.unavailable("SIGNING_UNAVAILABLE");
        }

        // Reuse an active IN_PROGRESS request instead of creating a duplicate.
        Optional<VerifactuAuthorization> activeOpt = store.findActiveByNif(nif);
        if (activeOpt.isPresent()) {
            VerifactuAuthorization active = activeOpt.get();
            if (active.status() == VerifactuAuthorizationStatus.IN_PROGRESS
                    && active.digitalSignRequestId() != null
                    && !active.digitalSignRequestId().isBlank()) {
                StartSigningResult resumed = tryResumeInProgress(active, configuredRole);
                if (resumed != null) {
                    return resumed;
                }
            } else if (active.status() == VerifactuAuthorizationStatus.DOWNLOAD_FAILED) {
                Optional<VerifactuAuthorization> refreshed = refreshForNif(nif);
                if (refreshed.isPresent()) {
                    if (refreshed.get().isCurrentlyValid()) {
                        return StartSigningResult.alreadyValid(refreshed.get());
                    }
                    if (refreshed.get().status().isActive()) {
                        return StartSigningResult.unavailable("DOWNLOAD_FAILED");
                    }
                }
            }
        }

        String templateId = digitalSignProperties.getVerifactu().getTemplateId().strip();
        DigitalSignTemplate template;
        try {
            template = digitalSignClient.getTemplate(templateId);
        } catch (DigitalSignException e) {
            log.warn("VERI*FACTU signing unavailable: GET /templates/{} failed ({}, code={})",
                    templateId, e.getKind(), e.getErrorCode());
            return StartSigningResult.unavailable("SIGNING_UNAVAILABLE");
        }

        TemplateSigner matchedSigner = findTemplateSigner(template.signers(), configuredRole);
        if (matchedSigner == null) {
            log.warn("VERI*FACTU signing unavailable: template '{}' does not contain configured signer-role '{}'",
                    templateId, configuredRole);
            return StartSigningResult.unavailable("SIGNING_UNAVAILABLE");
        }

        Map<String, String> values = buildAndValidateTemplateValues(template, fiscalProfile, nif);
        if (values == null) {
            return StartSigningResult.unavailable("SIGNING_UNAVAILABLE");
        }

        String signerName = resolveNewSignerName(ownerName, ownerUsername);
        String signerEmail = resolveNewSignerEmail(ownerEmail, fiscalProfile);
        if (signerEmail == null) {
            log.warn("VERI*FACTU signing unavailable: no valid email available for signer");
            return StartSigningResult.unavailable("SIGNING_UNAVAILABLE");
        }
        String dsLang = "en".equalsIgnoreCase(language) ? "en" : "es";

        UUID authorizationId = UUID.randomUUID();
        int attempt = store.nextAttemptForNif(nif);
        String idempotencyKey = "verifactu-" + nif + "-" + attempt;
        LocalDate expiresOn = LocalDate.now(clock).plusDays(SIGNING_EXPIRY_DAYS);

        SignerInput signerInput = new SignerInput(
                matchedSigner.role(),
                signerName,
                signerEmail,
                dsLang,
                "CERTIFICATE",
                nif);
        CreateRequestCommand command = new CreateRequestCommand(
                templateId,
                "Autorización VERI*FACTU - " + legalName,
                null,
                expiresOn,
                dsLang,
                authorizationId.toString(),
                false,
                List.of(signerInput),
                values,
                null);

        CreateRequestResult created;
        try {
            created = digitalSignClient.createRequest(command, idempotencyKey);
        } catch (DigitalSignException e) {
            log.warn("VERI*FACTU signing unavailable: POST /requests failed ({}, code={}, field={})",
                    e.getKind(), e.getErrorCode(), e.getField());
            return StartSigningResult.unavailable("SIGNING_UNAVAILABLE");
        }

        DigitalSignRequest remoteRequest = created.request();
        if (remoteRequest == null || remoteRequest.id() == null || remoteRequest.id().isBlank()) {
            log.warn("VERI*FACTU signing unavailable: Digital Sign returned a request without an id");
            return StartSigningResult.unavailable("SIGNING_UNAVAILABLE");
        }

        Instant now = clock.instant();
        VerifactuAuthorization record = new VerifactuAuthorization(
                authorizationId,
                nif,
                legalName,
                VerifactuAuthorizationStatus.IN_PROGRESS,
                truncate(remoteRequest.id().strip(), 64),
                idempotencyKey,
                attempt,
                truncate(templateId, 64),
                signerName,
                null,
                null,
                null,
                null,
                null,
                null,
                truncate(ownerUsername, 160),
                now,
                now,
                null,
                null,
                now,
                0,
                0,
                null);

        VerifactuAuthorization persisted;
        try {
            persisted = store.insert(record);
        } catch (DataIntegrityViolationException race) {
            Optional<VerifactuAuthorization> concurrent = store.findActiveByNif(nif);
            if (concurrent.isPresent() && concurrent.get().status() == VerifactuAuthorizationStatus.IN_PROGRESS) {
                StartSigningResult resumed = tryResumeInProgress(concurrent.get(), matchedSigner.role());
                if (resumed != null) {
                    return resumed;
                }
            }
            Optional<VerifactuAuthorization> concurrentValid = store.findValidByNif(nif);
            if (concurrentValid.isPresent() && concurrentValid.get().isCurrentlyValid()) {
                return StartSigningResult.alreadyValid(concurrentValid.get());
            }
            throw race;
        }

        String signingUrl = extractSigningUrl(remoteRequest, matchedSigner.role());
        if (signingUrl == null) {
            try {
                SigningUrlResponse urlResponse = digitalSignClient.createSigningUrl(
                        persisted.digitalSignRequestId(), matchedSigner.role());
                signingUrl = urlResponse != null && urlResponse.signingUrl() != null && !urlResponse.signingUrl().isBlank()
                        ? urlResponse.signingUrl().strip()
                        : null;
            } catch (DigitalSignException e) {
                log.warn("VERI*FACTU signing unavailable: POST /requests/{}/signers/{}/signing-url failed ({}, code={})",
                        persisted.digitalSignRequestId(), matchedSigner.role(), e.getKind(), e.getErrorCode());
                return StartSigningResult.unavailable("SIGNING_UNAVAILABLE");
            }
        }
        if (signingUrl == null) {
            log.warn("VERI*FACTU signing unavailable: Digital Sign did not return a signing URL for request {}",
                    persisted.digitalSignRequestId());
            return StartSigningResult.unavailable("SIGNING_UNAVAILABLE");
        }
        return StartSigningResult.signingUrl(signingUrl, persisted);
    }

    private StartSigningResult tryResumeInProgress(VerifactuAuthorization active, String role) {
        try {
            SigningUrlResponse response = digitalSignClient.createSigningUrl(active.digitalSignRequestId(), role);
            if (response != null && response.signingUrl() != null && !response.signingUrl().isBlank()) {
                return StartSigningResult.signingUrl(response.signingUrl().strip(), active);
            }
            return StartSigningResult.unavailable("SIGNING_UNAVAILABLE");
        } catch (DigitalSignException e) {
            if (e.getKind() == DigitalSignException.Kind.CONFLICT
                    || e.getKind() == DigitalSignException.Kind.NOT_FOUND
                    || e.getKind() == DigitalSignException.Kind.GONE) {
                Optional<VerifactuAuthorization> synced = syncRequest(
                        active.digitalSignRequestId(), clock.instant().plus(WEBHOOK_BUDGET), false, false);
                if (synced.isPresent()) {
                    if (synced.get().isCurrentlyValid()) {
                        return StartSigningResult.alreadyValid(synced.get());
                    }
                    if (synced.get().status().isTerminal()) {
                        return null;
                    }
                }
            }
            log.warn("VERI*FACTU resume signing failed for request {} ({}, code={})",
                    active.digitalSignRequestId(), e.getKind(), e.getErrorCode());
            return StartSigningResult.unavailable("SIGNING_UNAVAILABLE");
        }
    }

    private String configuredSignerRole() {
        if (digitalSignProperties.getVerifactu() == null) {
            return null;
        }
        String role = digitalSignProperties.getVerifactu().getSignerRole();
        return (role == null || role.isBlank()) ? null : role.strip();
    }

    private static TemplateSigner findTemplateSigner(List<TemplateSigner> signers, String expectedRole) {
        if (signers == null || signers.isEmpty() || expectedRole == null) {
            return null;
        }
        for (TemplateSigner signer : signers) {
            if (signer != null && signer.role() != null && expectedRole.equalsIgnoreCase(signer.role().strip())) {
                return signer;
            }
        }
        return null;
    }

    private Map<String, String> buildAndValidateTemplateValues(
            DigitalSignTemplate template,
            FiscalProfile fiscalProfile,
            String nif) {
        DigitalSignProperties.Fields fieldKeys = digitalSignProperties.getVerifactu().getFields();
        Map<String, String> configuredValues = new LinkedHashMap<>();
        putIfConfigured(configuredValues, fieldKeys.getLegalName(), fiscalProfile.legalName());
        putIfConfigured(configuredValues, fieldKeys.getNif(), nif);
        putIfConfigured(configuredValues, fieldKeys.getAddress(), formatFiscalAddress(fiscalProfile));
        putIfConfigured(configuredValues, fieldKeys.getBrasatechName(), brasatechLegalName);
        putIfConfigured(configuredValues, fieldKeys.getBrasatechNif(), brasatechTaxId);
        putIfConfigured(configuredValues, fieldKeys.getBrasatechAddress(), brasatechAddress);

        Map<String, String> valuesToSend = new LinkedHashMap<>();
        for (TemplateField field : template.fields()) {
            if (field == null || field.key() == null || field.key().isBlank()) {
                continue;
            }
            if (!"TEXT".equalsIgnoreCase(field.type())) {
                continue;
            }
            String key = field.key().strip();
            String value = configuredValues.get(key);
            if (value == null || value.isBlank()) {
                if (field.required()) {
                    log.warn("VERI*FACTU signing unavailable: missing value for required template TEXT field key '{}'", key);
                    return null;
                }
                continue;
            }
            if (!matchesTemplateValidation(value, field.validation())) {
                log.warn("VERI*FACTU signing unavailable: value for template TEXT field key '{}' failed validation '{}'",
                        key, field.validation());
                return null;
            }
            valuesToSend.put(key, value);
        }
        return valuesToSend;
    }

    private static void putIfConfigured(Map<String, String> target, String key, String value) {
        if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
            target.put(key.strip(), value.strip());
        }
    }

    private static String formatFiscalAddress(FiscalProfile profile) {
        StringBuilder sb = new StringBuilder();
        if (profile.addressLine() != null && !profile.addressLine().isBlank()) {
            sb.append(profile.addressLine().strip());
        }
        StringBuilder locality = new StringBuilder();
        if (profile.postalCode() != null && !profile.postalCode().isBlank()) {
            locality.append(profile.postalCode().strip());
        }
        if (profile.city() != null && !profile.city().isBlank()) {
            if (!locality.isEmpty()) {
                locality.append(' ');
            }
            locality.append(profile.city().strip());
        }
        if (profile.province() != null && !profile.province().isBlank()) {
            if (!locality.isEmpty()) {
                locality.append(" (").append(profile.province().strip()).append(')');
            } else {
                locality.append(profile.province().strip());
            }
        }
        if (!locality.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(locality);
        }
        return sb.toString();
    }

    static boolean matchesTemplateValidation(String value, String validation) {
        if (validation == null || validation.isBlank() || "NONE".equalsIgnoreCase(validation.strip())) {
            return true;
        }
        String trimmed = value == null ? "" : value.strip();
        return switch (validation.strip().toUpperCase(Locale.ROOT)) {
            case "DNI_NIE" -> SpanishTaxId.isValid(trimmed);
            case "EMAIL" -> trimmed.length() <= 254 && EMAIL_PATTERN.matcher(trimmed).matches();
            case "PHONE" -> PHONE_PATTERN.matcher(trimmed).matches();
            case "IBAN" -> IBAN_PATTERN.matcher(trimmed.replaceAll("\\s+", "").toUpperCase(Locale.ROOT)).matches();
            case "DATE" -> {
                try {
                    LocalDate.parse(trimmed);
                    yield true;
                } catch (DateTimeParseException e) {
                    yield false;
                }
            }
            default -> false;
        };
    }

    private static String resolveNewSignerName(String ownerName, String ownerUsername) {
        if (ownerName != null && !ownerName.isBlank()) {
            return truncate(ownerName.strip(), 120);
        }
        return truncate(ownerUsername == null ? "" : ownerUsername.strip(), 120);
    }

    private static String resolveNewSignerEmail(String ownerEmail, FiscalProfile profile) {
        if (ownerEmail != null && !ownerEmail.isBlank() && EMAIL_PATTERN.matcher(ownerEmail.strip()).matches()) {
            return ownerEmail.strip();
        }
        if (profile != null && profile.email() != null && !profile.email().isBlank()
                && EMAIL_PATTERN.matcher(profile.email().strip()).matches()) {
            return profile.email().strip();
        }
        return null;
    }

    private static String extractSigningUrl(DigitalSignRequest request, String role) {
        if (request == null || request.signers() == null || role == null) {
            return null;
        }
        for (RequestSigner signer : request.signers()) {
            if (signer != null && signer.role() != null && role.equalsIgnoreCase(signer.role().strip())
                    && signer.signingUrl() != null && !signer.signingUrl().isBlank()) {
                return signer.signingUrl().strip();
            }
        }
        return null;
    }

    /**
     * Processes a signature-verified Digital Sign webhook synchronously within the {@link #WEBHOOK_BUDGET}.
     * If processing throws unexpectedly, the deduplication row is removed so Digital Sign can redeliver.
     */
    public WebhookOutcome handleWebhook(String eventId, String eventType, String requestId) {
        Instant now = clock.instant();
        String safeEventType = (eventType == null || eventType.isBlank()) ? "unknown" : truncate(eventType.strip(), 64);
        String safeRequestId = (requestId == null || requestId.isBlank()) ? null : truncate(requestId.strip(), 64);

        if (eventId != null && !eventId.isBlank()) {
            String normalizedEventId = truncate(eventId.strip(), 128);
            boolean recorded = store.recordWebhookEventIfNew(normalizedEventId, safeEventType, safeRequestId, now);
            if (!recorded) {
                log.debug("Ignoring duplicate Digital Sign webhook eventId={}", normalizedEventId);
                return WebhookOutcome.DUPLICATE;
            }
            try {
                if (safeRequestId != null) {
                    syncRequestForWebhook(safeRequestId);
                }
                store.markWebhookEventProcessed(normalizedEventId, clock.instant());
                return WebhookOutcome.PROCESSED;
            } catch (RuntimeException e) {
                try {
                    store.deleteWebhookEvent(normalizedEventId);
                } catch (RuntimeException cleanupError) {
                    log.warn("Failed to remove webhook event {} after processing error", normalizedEventId, cleanupError);
                }
                throw e;
            }
        }

        if (safeRequestId != null) {
            syncRequestForWebhook(safeRequestId);
        }
        return WebhookOutcome.PROCESSED;
    }

    /** Synchronises a request on the synchronous webhook path (~8 s total budget, no retries). */
    public Optional<VerifactuAuthorization> syncRequestForWebhook(String requestId) {
        return syncRequest(requestId, clock.instant().plus(WEBHOOK_BUDGET), false);
    }

    /**
     * Fetches {@code GET /requests/{id}} from Digital Sign for active ({@code IN_PROGRESS} or
     * {@code DOWNLOAD_FAILED}) authorisations, verifies the configured signer role's certificate NIF,
     * downloads both PDFs privately when completed, and persists the resulting state via CAS update.
     *
     * <p>If a concurrent writer advances the row while still active, retries the sync once so a
     * concurrent {@code IN_PROGRESS} refresh cannot drop a {@code VALID} write.
     */
    public Optional<VerifactuAuthorization> syncRequest(String requestId, Instant deadline, boolean allowRetries) {
        return syncRequest(requestId, deadline, allowRetries, true, 0);
    }

    public Optional<VerifactuAuthorization> syncRequest(
            String requestId,
            Instant deadline,
            boolean allowRetries,
            boolean countFailure) {
        return syncRequest(requestId, deadline, allowRetries, countFailure, 0);
    }

    private Optional<VerifactuAuthorization> syncRequest(
            String requestId,
            Instant deadline,
            boolean allowRetries,
            boolean countFailure,
            int casAttempt) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        Optional<VerifactuAuthorization> existingOpt = store.findByDigitalSignRequestId(requestId.strip());
        if (existingOpt.isEmpty()) {
            log.debug("Ignoring Digital Sign sync for unknown requestId={}", requestId);
            return Optional.empty();
        }
        VerifactuAuthorization current = existingOpt.get();
        if (!current.status().isActive()) {
            return Optional.of(current);
        }
        if (!digitalSignClient.isConfigured()) {
            log.warn("Cannot sync Digital Sign request {}: Digital Sign client is not configured", requestId);
            return Optional.of(current);
        }

        CallOptions options = allowRetries
                ? CallOptions.withDeadline(deadline)
                : CallOptions.withDeadlineNoRetries(deadline);

        DigitalSignRequest remote;
        try {
            remote = digitalSignClient.getRequest(requestId.strip(), options);
        } catch (DigitalSignException e) {
            log.warn("Digital Sign GET /requests/{} failed ({}): {}", requestId, e.getKind(), e.getMessage());
            if (e.getKind() == DigitalSignException.Kind.NOT_FOUND || e.getKind() == DigitalSignException.Kind.GONE) {
                Instant now = clock.instant();
                String reason = e.getKind() == DigitalSignException.Kind.NOT_FOUND
                        ? "REQUEST_NOT_FOUND"
                        : "DOCUMENTS_GONE";
                VerifactuAuthorization candidate = buildBoundedSyncFailure(current, current.signerName(),
                        current.certificateNif(), current.status(), reason, now, countFailure);
                return persistWithCasRetry(candidate, requestId, deadline, allowRetries, countFailure, casAttempt);
            }
            return Optional.of(current);
        }

        Instant now = clock.instant();
        String remoteStatus = remote.status() == null ? "" : remote.status().strip().toUpperCase(Locale.ROOT);
        RequestSigner configuredSigner = findConfiguredSigner(remote.signers());
        String signerName = resolveSignerName(configuredSigner, current.signerName());

        VerifactuAuthorization candidate = switch (remoteStatus) {
            case "DECLINED" -> current.withSync(
                    VerifactuAuthorizationStatus.DECLINED,
                    signerName,
                    current.certificateNif(),
                    current.signedPdfPath(),
                    current.signedPdfSha256(),
                    current.evidencePdfPath(),
                    current.evidencePdfSha256(),
                    "DECLINED",
                    current.validFrom(),
                    now,
                    0,
                    null);
            case "EXPIRED" -> current.withSync(
                    VerifactuAuthorizationStatus.EXPIRED,
                    signerName,
                    current.certificateNif(),
                    current.signedPdfPath(),
                    current.signedPdfSha256(),
                    current.evidencePdfPath(),
                    current.evidencePdfSha256(),
                    "EXPIRED",
                    current.validFrom(),
                    now,
                    0,
                    null);
            case "CANCELLED" -> current.withSync(
                    VerifactuAuthorizationStatus.CANCELLED,
                    signerName,
                    current.certificateNif(),
                    current.signedPdfPath(),
                    current.signedPdfSha256(),
                    current.evidencePdfPath(),
                    current.evidencePdfSha256(),
                    "CANCELLED",
                    current.validFrom(),
                    now,
                    0,
                    null);
            case "IN_PROGRESS" -> current.withSync(
                    VerifactuAuthorizationStatus.IN_PROGRESS,
                    signerName,
                    current.certificateNif(),
                    current.signedPdfPath(),
                    current.signedPdfSha256(),
                    current.evidencePdfPath(),
                    current.evidencePdfSha256(),
                    null,
                    current.validFrom(),
                    now,
                    0,
                    null);
            case "COMPLETED" -> buildCompletedCandidate(current, remote, configuredSigner, signerName, options, deadline, countFailure);
            default -> {
                log.warn("Unexpected Digital Sign status '{}' for request {}", remote.status(), requestId);
                yield null;
            }
        };

        if (candidate == null) {
            return Optional.of(current);
        }
        return persistWithCasRetry(candidate, requestId, deadline, allowRetries, countFailure, casAttempt);
    }

    private Optional<VerifactuAuthorization> persistWithCasRetry(
            VerifactuAuthorization candidate,
            String requestId,
            Instant deadline,
            boolean allowRetries,
            boolean countFailure,
            int casAttempt) {
        UpdateResult result = store.compareAndSet(candidate);
        if (result.applied()) {
            return Optional.of(result.authorization());
        }
        VerifactuAuthorization latest = store.findById(candidate.id()).orElse(result.authorization());
        if (casAttempt < MAX_CAS_RETRIES && latest.status().isActive()) {
            log.debug("Lost CAS update for VERI*FACTU authorisation {} while still {}; retrying sync once",
                    candidate.id(), latest.status());
            return syncRequest(requestId, deadline, allowRetries, countFailure, casAttempt + 1);
        }
        return Optional.of(latest);
    }

    private VerifactuAuthorization buildBoundedSyncFailure(
            VerifactuAuthorization current,
            String signerName,
            String certificateNif,
            VerifactuAuthorizationStatus activeFallbackStatus,
            String failureReason,
            Instant now,
            boolean countFailure) {
        if (!countFailure) {
            return current.withSync(
                    activeFallbackStatus,
                    signerName,
                    certificateNif,
                    current.signedPdfPath(),
                    current.signedPdfSha256(),
                    current.evidencePdfPath(),
                    current.evidencePdfSha256(),
                    failureReason,
                    null,
                    now,
                    current.syncFailureCount(),
                    current.syncFailureSince());
        }
        int nextFailures = current.syncFailureCount() + 1;
        Instant failureSince = current.syncFailureSince() != null ? current.syncFailureSince() : now;
        boolean agedOut = !now.isBefore(failureSince.plus(MAX_SYNC_FAILURE_AGE));
        if (nextFailures >= MAX_SYNC_FAILURE_ATTEMPTS || agedOut) {
            log.warn("VERI*FACTU authorisation {} reached bounded retry limit (attempts={}, agedOut={}, reason={}); moving to terminal DOCUMENTS_GONE",
                    current.id(), nextFailures, agedOut, failureReason);
            return current.withSync(
                    VerifactuAuthorizationStatus.DOCUMENTS_GONE,
                    signerName,
                    certificateNif,
                    current.signedPdfPath(),
                    current.signedPdfSha256(),
                    current.evidencePdfPath(),
                    current.evidencePdfSha256(),
                    failureReason,
                    null,
                    now,
                    nextFailures,
                    failureSince);
        }
        return current.withSync(
                activeFallbackStatus,
                signerName,
                certificateNif,
                current.signedPdfPath(),
                current.signedPdfSha256(),
                current.evidencePdfPath(),
                current.evidencePdfSha256(),
                failureReason,
                null,
                now,
                nextFailures,
                failureSince);
    }

    /**
     * Reconciles {@code DOWNLOAD_FAILED} and stale {@code IN_PROGRESS} authorisations and purges
     * webhook events older than 30 days. Wired into {@code POST /internal/jobs/run}.
     */
    public ReconcileSummary reconcile(Instant deadline) {
        int purged = purgeOldWebhookEvents();
        if (!digitalSignClient.isConfigured()) {
            return new ReconcileSummary(0, 0, purged);
        }
        Instant staleBefore = clock.instant().minus(STALE_IN_PROGRESS_AGE);
        List<VerifactuAuthorization> due = store.findDueForReconciliation(staleBefore, DEFAULT_RECONCILE_BATCH_SIZE);
        int checked = 0;
        int updated = 0;
        for (VerifactuAuthorization auth : due) {
            Instant now = clock.instant();
            if (deadline != null && !now.isBefore(deadline)) {
                break;
            }
            if (auth.digitalSignRequestId() == null || auth.digitalSignRequestId().isBlank()) {
                continue;
            }
            Instant itemDeadline = computeItemDeadline(now, deadline);
            VerifactuAuthorizationStatus before = auth.status();
            Optional<VerifactuAuthorization> after = syncRequest(auth.digitalSignRequestId(), itemDeadline, true);
            checked++;
            if (after.isPresent() && after.get().status() != before) {
                updated++;
            }
        }
        return new ReconcileSummary(checked, updated, purged);
    }

    /** Purges {@code digital_sign_webhook_events} rows older than 30 days. */
    public int purgeOldWebhookEvents() {
        return store.purgeWebhookEventsOlderThan(clock.instant().minus(WEBHOOK_EVENT_RETENTION));
    }

    private VerifactuAuthorization buildCompletedCandidate(
            VerifactuAuthorization current,
            DigitalSignRequest remote,
            RequestSigner configuredSigner,
            String signerName,
            CallOptions options,
            Instant deadline,
            boolean countFailure) {

        Instant now = clock.instant();
        Object certificateData = configuredSigner == null ? null : configuredSigner.certificate();
        VerificationResult verification = CertificateNifExtractor.verify(certificateData, current.nif());

        if (verification.status() == CertificateNifExtractor.Status.MISMATCH) {
            String foundNif = verification.candidates().stream().findFirst().orElse(null);
            log.warn("VERI*FACTU certificate NIF mismatch for authorisation {} (expected={}, extracted={})",
                    current.id(), maskNif(current.nif()), maskNif(foundNif));
            return current.withSync(
                    VerifactuAuthorizationStatus.NIF_MISMATCH,
                    signerName,
                    truncate(foundNif, 32),
                    current.signedPdfPath(),
                    current.signedPdfSha256(),
                    current.evidencePdfPath(),
                    current.evidencePdfSha256(),
                    "NIF_MISMATCH",
                    null,
                    now,
                    0,
                    null);
        }

        if (verification.status() == CertificateNifExtractor.Status.UNVERIFIED) {
            log.warn("VERI*FACTU certificate NIF could not be verified for authorisation {} (nif={})",
                    current.id(), maskNif(current.nif()));
            return current.withSync(
                    VerifactuAuthorizationStatus.NIF_UNVERIFIED,
                    signerName,
                    null,
                    current.signedPdfPath(),
                    current.signedPdfSha256(),
                    current.evidencePdfPath(),
                    current.evidencePdfSha256(),
                    "NIF_UNVERIFIED",
                    null,
                    now,
                    0,
                    null);
        }

        String matchedNif = truncate(verification.matchedNif(), 32);
        if (remote.documents() == null
                || !remote.documents().signedDocument()
                || !remote.documents().evidenceReport()) {
            log.warn("Digital Sign request {} is COMPLETED but documents are not yet ready", current.digitalSignRequestId());
            return buildBoundedSyncFailure(current, signerName, matchedNif,
                    VerifactuAuthorizationStatus.DOWNLOAD_FAILED, "DOCUMENTS_NOT_READY",
                    now, countFailure);
        }

        String signedPath = current.signedPdfPath();
        String signedSha = current.signedPdfSha256();
        String evidencePath = current.evidencePdfPath();
        String evidenceSha = current.evidencePdfSha256();

        try {
            if (!isStored(signedPath, signedSha)) {
                checkDeadline(deadline, "signed-document");
                byte[] signedBytes = digitalSignClient.downloadSignedDocument(current.digitalSignRequestId(), options);
                StoredDocument storedSigned = documentStorage.storeSignedPdf(current.nif(), current.id(), signedBytes);
                signedPath = storedSigned.relativePath();
                signedSha = storedSigned.sha256();
            }
            if (!isStored(evidencePath, evidenceSha)) {
                checkDeadline(deadline, "evidence-report");
                byte[] evidenceBytes = digitalSignClient.downloadEvidenceReport(current.digitalSignRequestId(), options);
                StoredDocument storedEvidence = documentStorage.storeEvidencePdf(current.nif(), current.id(), evidenceBytes);
                evidencePath = storedEvidence.relativePath();
                evidenceSha = storedEvidence.sha256();
            }
        } catch (RuntimeException e) {
            if (e instanceof DigitalSignException dse && dse.getKind() == DigitalSignException.Kind.GONE) {
                log.warn("Digital Sign purged documents (410 Gone) for authorisation {}; marking terminal DOCUMENTS_GONE",
                        current.id());
                return current.withSync(
                        VerifactuAuthorizationStatus.DOCUMENTS_GONE,
                        signerName,
                        matchedNif,
                        signedPath,
                        signedSha,
                        evidencePath,
                        evidenceSha,
                        "DOCUMENTS_GONE",
                        null,
                        clock.instant(),
                        current.syncFailureCount());
            }
            String reason = classifyDownloadFailure(e);
            log.warn("Failed to download/store VERI*FACTU PDFs for authorisation {} (reason={}): {}",
                    current.id(), reason, e.getMessage());
            return current.withSync(
                    VerifactuAuthorizationStatus.DOWNLOAD_FAILED,
                    signerName,
                    matchedNif,
                    signedPath,
                    signedSha,
                    evidencePath,
                    evidenceSha,
                    reason,
                    null,
                    clock.instant(),
                    current.syncFailureCount());
        }

        Instant validTimestamp = clock.instant();
        Instant validFrom = current.validFrom() != null ? current.validFrom() : validTimestamp;
        return current.withSync(
                VerifactuAuthorizationStatus.VALID,
                signerName,
                matchedNif,
                signedPath,
                signedSha,
                evidencePath,
                evidenceSha,
                null,
                validFrom,
                validTimestamp,
                0);
    }

    private boolean isStored(String path, String sha256) {
        return path != null && !path.isBlank()
                && sha256 != null && !sha256.isBlank()
                && documentStorage.exists(path);
    }

    private void checkDeadline(Instant deadline, String stage) {
        if (deadline != null && !clock.instant().isBefore(deadline)) {
            throw new DigitalSignException(
                    DigitalSignException.Kind.NETWORK_ERROR, 0, "deadline_exceeded", null,
                    "Webhook/sync deadline exceeded before downloading " + stage);
        }
    }

    private RequestSigner findConfiguredSigner(List<RequestSigner> signers) {
        if (signers == null || signers.isEmpty()) {
            return null;
        }
        String expectedRole = digitalSignProperties.getVerifactu() == null
                ? null
                : digitalSignProperties.getVerifactu().getSignerRole();
        if (expectedRole == null || expectedRole.isBlank()) {
            return null;
        }
        String target = expectedRole.strip();
        for (RequestSigner signer : signers) {
            if (signer != null && signer.role() != null && target.equalsIgnoreCase(signer.role().strip())) {
                return signer;
            }
        }
        return null;
    }

    private static String resolveSignerName(RequestSigner signer, String fallback) {
        if (signer != null && signer.name() != null && !signer.name().isBlank()) {
            return truncate(signer.name().strip(), 120);
        }
        return fallback;
    }

    private static String classifyDownloadFailure(RuntimeException e) {
        if (e instanceof DigitalSignException dse) {
            if ("deadline_exceeded".equals(dse.getErrorCode())) {
                return "DOWNLOAD_TIMEOUT";
            }
            return "DOWNLOAD_FAILED";
        }
        if (e instanceof IllegalArgumentException) {
            return "INVALID_PDF";
        }
        return "DOWNLOAD_FAILED";
    }

    static String maskNif(String nif) {
        if (nif == null || nif.isBlank()) {
            return "<empty>";
        }
        String trimmed = nif.strip();
        if (trimmed.length() <= 5) {
            return "****";
        }
        return trimmed.substring(0, 2) + "****" + trimmed.substring(trimmed.length() - 3);
    }

    private static Instant computeItemDeadline(Instant now, Instant overallDeadline) {
        Instant itemCap = now.plus(RECONCILE_ITEM_BUDGET);
        if (overallDeadline == null) {
            return itemCap;
        }
        return itemCap.isBefore(overallDeadline) ? itemCap : overallDeadline;
    }

    private static String normalizeNif(String nif) {
        if (nif == null || nif.isBlank()) {
            return null;
        }
        return SpanishTaxId.parse(nif)
                .map(SpanishTaxId::value)
                .orElseGet(() -> nif.strip().toUpperCase(Locale.ROOT));
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
