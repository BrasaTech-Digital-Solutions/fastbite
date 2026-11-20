package es.brasatech.fastbite.application.verifactu;

import es.brasatech.fastbite.application.digitalsign.CallOptions;
import es.brasatech.fastbite.application.digitalsign.CertificateNifExtractor;
import es.brasatech.fastbite.application.digitalsign.CertificateNifExtractor.VerificationResult;
import es.brasatech.fastbite.application.digitalsign.DigitalSignClient;
import es.brasatech.fastbite.application.digitalsign.DigitalSignException;
import es.brasatech.fastbite.application.digitalsign.DigitalSignProperties;
import es.brasatech.fastbite.application.digitalsign.DigitalSignRequest;
import es.brasatech.fastbite.application.digitalsign.DigitalSignRequest.RequestSigner;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationDocumentStorage.StoredDocument;
import es.brasatech.fastbite.domain.fiscal.SpanishTaxId;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorization;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorizationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Coordinates VERI*FACTU authorisation state synchronisation, certificate NIF verification,
 * private PDF downloads, webhook deduplication, and background reconciliation.
 */
@Service
public class VerifactuAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(VerifactuAuthorizationService.class);

    /** Total synchronous budget for webhook handling on Cloud Run (~8 s < Digital Sign's 10 s limit). */
    public static final Duration WEBHOOK_BUDGET = Duration.ofSeconds(8);
    /** Age without a sync after which an IN_PROGRESS request is polled by reconciliation. */
    public static final Duration STALE_IN_PROGRESS_AGE = Duration.ofHours(1);
    /** Retention period for webhook deduplication records. */
    public static final Duration WEBHOOK_EVENT_RETENTION = Duration.ofDays(30);
    /** Per-item budget during background reconciliation so one slow request cannot starve others. */
    public static final Duration RECONCILE_ITEM_BUDGET = Duration.ofSeconds(15);
    public static final int DEFAULT_RECONCILE_BATCH_SIZE = 50;

    public enum WebhookOutcome {
        PROCESSED,
        DUPLICATE
    }

    public record ReconcileSummary(int checked, int updated, int webhookEventsPurged) {
        public static final ReconcileSummary EMPTY = new ReconcileSummary(0, 0, 0);
    }

    private final VerifactuAuthorizationStore store;
    private final VerifactuAuthorizationDocumentStorage documentStorage;
    private final DigitalSignClient digitalSignClient;
    private final DigitalSignProperties digitalSignProperties;
    private final Clock clock;

    public VerifactuAuthorizationService(
            VerifactuAuthorizationStore store,
            VerifactuAuthorizationDocumentStorage documentStorage,
            DigitalSignClient digitalSignClient,
            DigitalSignProperties digitalSignProperties,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.documentStorage = Objects.requireNonNull(documentStorage, "documentStorage");
        this.digitalSignClient = Objects.requireNonNull(digitalSignClient, "digitalSignClient");
        this.digitalSignProperties = Objects.requireNonNull(digitalSignProperties, "digitalSignProperties");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** True only when the NIF currently has a VALID authorisation with both PDFs stored. */
    public boolean hasValidAuthorization(String nif) {
        String normalized = normalizeNif(nif);
        return normalized != null && store.hasValidAuthorization(normalized);
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
     * User-driven refresh for the opening page or "Comprobar ahora" button: if the NIF has an
     * {@code IN_PROGRESS} or {@code DOWNLOAD_FAILED} authorisation, refreshes it with a tight budget.
     */
    public Optional<VerifactuAuthorization> refreshForNif(String nif) {
        String normalized = normalizeNif(nif);
        if (normalized == null) {
            return Optional.empty();
        }
        Optional<VerifactuAuthorization> active = store.findActiveByNif(normalized);
        if (active.isPresent() && active.get().digitalSignRequestId() != null
                && !active.get().digitalSignRequestId().isBlank()) {
            return syncRequest(active.get().digitalSignRequestId(), clock.instant().plus(WEBHOOK_BUDGET), false);
        }
        return findCurrentForNif(normalized);
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
     */
    public Optional<VerifactuAuthorization> syncRequest(String requestId, Instant deadline, boolean allowRetries) {
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
            return Optional.of(current);
        }

        Instant now = clock.instant();
        String remoteStatus = remote.status() == null ? "" : remote.status().strip().toUpperCase(Locale.ROOT);
        RequestSigner configuredSigner = findConfiguredSigner(remote.signers());
        String signerName = resolveSignerName(configuredSigner, current.signerName());

        return switch (remoteStatus) {
            case "DECLINED" -> Optional.of(store.update(current.withSync(
                    VerifactuAuthorizationStatus.DECLINED,
                    signerName,
                    current.certificateNif(),
                    current.signedPdfPath(),
                    current.signedPdfSha256(),
                    current.evidencePdfPath(),
                    current.evidencePdfSha256(),
                    "DECLINED",
                    current.validFrom(),
                    now)));
            case "EXPIRED" -> Optional.of(store.update(current.withSync(
                    VerifactuAuthorizationStatus.EXPIRED,
                    signerName,
                    current.certificateNif(),
                    current.signedPdfPath(),
                    current.signedPdfSha256(),
                    current.evidencePdfPath(),
                    current.evidencePdfSha256(),
                    "EXPIRED",
                    current.validFrom(),
                    now)));
            case "CANCELLED" -> Optional.of(store.update(current.withSync(
                    VerifactuAuthorizationStatus.CANCELLED,
                    signerName,
                    current.certificateNif(),
                    current.signedPdfPath(),
                    current.signedPdfSha256(),
                    current.evidencePdfPath(),
                    current.evidencePdfSha256(),
                    "CANCELLED",
                    current.validFrom(),
                    now)));
            case "IN_PROGRESS" -> Optional.of(store.update(current.withSync(
                    VerifactuAuthorizationStatus.IN_PROGRESS,
                    signerName,
                    current.certificateNif(),
                    current.signedPdfPath(),
                    current.signedPdfSha256(),
                    current.evidencePdfPath(),
                    current.evidencePdfSha256(),
                    null,
                    current.validFrom(),
                    now)));
            case "COMPLETED" -> Optional.of(handleCompleted(current, remote, configuredSigner, signerName, options, deadline));
            default -> {
                log.warn("Unexpected Digital Sign status '{}' for request {}", remote.status(), requestId);
                yield Optional.of(current);
            }
        };
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

    private VerifactuAuthorization handleCompleted(
            VerifactuAuthorization current,
            DigitalSignRequest remote,
            RequestSigner configuredSigner,
            String signerName,
            CallOptions options,
            Instant deadline) {

        Instant now = clock.instant();
        Object certificateData = configuredSigner == null ? null : configuredSigner.certificate();
        VerificationResult verification = CertificateNifExtractor.verify(certificateData, current.nif());

        if (verification.status() == CertificateNifExtractor.Status.MISMATCH) {
            String foundNif = verification.candidates().stream().findFirst().orElse(null);
            log.warn("VERI*FACTU certificate NIF mismatch for authorisation {} (expected={}, extracted={})",
                    current.id(), maskNif(current.nif()), maskNif(foundNif));
            return store.update(current.withSync(
                    VerifactuAuthorizationStatus.NIF_MISMATCH,
                    signerName,
                    truncate(foundNif, 32),
                    current.signedPdfPath(),
                    current.signedPdfSha256(),
                    current.evidencePdfPath(),
                    current.evidencePdfSha256(),
                    "NIF_MISMATCH",
                    null,
                    now));
        }

        if (verification.status() == CertificateNifExtractor.Status.UNVERIFIED) {
            log.warn("VERI*FACTU certificate NIF could not be verified for authorisation {} (nif={})",
                    current.id(), maskNif(current.nif()));
            return store.update(current.withSync(
                    VerifactuAuthorizationStatus.NIF_UNVERIFIED,
                    signerName,
                    null,
                    current.signedPdfPath(),
                    current.signedPdfSha256(),
                    current.evidencePdfPath(),
                    current.evidencePdfSha256(),
                    "NIF_UNVERIFIED",
                    null,
                    now));
        }

        String matchedNif = truncate(verification.matchedNif(), 32);
        if (remote.documents() == null
                || !remote.documents().signedDocument()
                || !remote.documents().evidenceReport()) {
            log.warn("Digital Sign request {} is COMPLETED but documents are not yet ready", current.digitalSignRequestId());
            return store.update(current.withSync(
                    VerifactuAuthorizationStatus.DOWNLOAD_FAILED,
                    signerName,
                    matchedNif,
                    current.signedPdfPath(),
                    current.signedPdfSha256(),
                    current.evidencePdfPath(),
                    current.evidencePdfSha256(),
                    "DOCUMENTS_NOT_READY",
                    null,
                    now));
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
                return store.update(current.withSync(
                        VerifactuAuthorizationStatus.DOCUMENTS_GONE,
                        signerName,
                        matchedNif,
                        signedPath,
                        signedSha,
                        evidencePath,
                        evidenceSha,
                        "DOCUMENTS_GONE",
                        null,
                        clock.instant()));
            }
            String reason = classifyDownloadFailure(e);
            log.warn("Failed to download/store VERI*FACTU PDFs for authorisation {} (reason={}): {}",
                    current.id(), reason, e.getMessage());
            return store.update(current.withSync(
                    VerifactuAuthorizationStatus.DOWNLOAD_FAILED,
                    signerName,
                    matchedNif,
                    signedPath,
                    signedSha,
                    evidencePath,
                    evidenceSha,
                    reason,
                    null,
                    clock.instant()));
        }

        Instant validTimestamp = clock.instant();
        Instant validFrom = current.validFrom() != null ? current.validFrom() : validTimestamp;
        return store.update(current.withSync(
                VerifactuAuthorizationStatus.VALID,
                signerName,
                matchedNif,
                signedPath,
                signedSha,
                evidencePath,
                evidenceSha,
                null,
                validFrom,
                validTimestamp));
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
