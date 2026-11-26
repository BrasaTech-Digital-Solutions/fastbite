package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.tenant.MarketingUnsubscribeLink;
import es.brasatech.fastbite.application.tenant.PendingSignupService;
import es.brasatech.fastbite.application.mail.AccountMailOutboxService;
import es.brasatech.fastbite.application.platform.PromotionService;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationService;
import es.brasatech.fastbite.application.verifactu.VerifactuNotificationService;
import es.brasatech.fastbite.service.ContractLifecycleJob;
import es.brasatech.fastbite.service.FiscalIssuerBackfillJob;
import es.brasatech.fastbite.service.SandboxDeletionSyncJob;
import es.brasatech.fastbite.service.SandboxProvisioningJob;
import es.brasatech.fastbite.service.VerifactuSubmissionJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;

/**
 * Runs the environment's background jobs inside an HTTP request, for Cloud Scheduler on Cloud Run
 * (where CPU is allocated only during requests). Answered only when {@code FASTBITE_JOBS_SECRET} is
 * configured and sent in {@code X-FastBite-Jobs-Secret}; sessions and roles grant no access.
 */
@RestController
@Slf4j
public class InternalJobsController {

    public static final String SECRET_HEADER = "X-FastBite-Jobs-Secret";
    static final int MIN_SECRET_LENGTH = 32;

    public record JobRunSummary(
            int sandboxProvisioned,
            int verifactuSubmitted,
            int verifactuLocationsChecked,
            int verifactuLocationsRemaining,
            int fiscalIssuersBackfilled,
            int contractLifecycleActions,
            int promotionsSwept,
            int accountTokensDeleted,
            int accountEmailLimitsDeleted,
            int accountEmailOutboxDeleted,
            // VERI*FACTU authorisation reconciliation & webhook event retention
            int verifactuAuthorizationsChecked,
            int verifactuAuthorizationsUpdated,
            int digitalSignWebhookEventsPurged) {
        public JobRunSummary(
                int sandboxProvisioned,
                int verifactuSubmitted,
                int verifactuLocationsChecked,
                int verifactuLocationsRemaining,
                int fiscalIssuersBackfilled,
                int contractLifecycleActions,
                int promotionsSwept,
                int accountTokensDeleted,
                int accountEmailLimitsDeleted,
                int accountEmailOutboxDeleted) {
            this(sandboxProvisioned, verifactuSubmitted, verifactuLocationsChecked, verifactuLocationsRemaining,
                    fiscalIssuersBackfilled, contractLifecycleActions, promotionsSwept,
                    accountTokensDeleted, accountEmailLimitsDeleted, accountEmailOutboxDeleted, 0, 0, 0);
        }
        public JobRunSummary(
                int sandboxProvisioned,
                int verifactuSubmitted,
                int verifactuLocationsChecked,
                int verifactuLocationsRemaining,
                int fiscalIssuersBackfilled,
                int contractLifecycleActions) {
            this(sandboxProvisioned, verifactuSubmitted, verifactuLocationsChecked, verifactuLocationsRemaining,
                    fiscalIssuersBackfilled, contractLifecycleActions, 0, 0, 0, 0, 0, 0, 0);
        }
        public JobRunSummary(
                int sandboxProvisioned,
                int verifactuSubmitted,
                int verifactuLocationsChecked,
                int verifactuLocationsRemaining,
                int fiscalIssuersBackfilled) {
            this(sandboxProvisioned, verifactuSubmitted, verifactuLocationsChecked, verifactuLocationsRemaining,
                    fiscalIssuersBackfilled, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private final SandboxProvisioningJob sandboxProvisioning;
    private final VerifactuSubmissionJob verifactuSubmission;
    private final FiscalIssuerBackfillJob fiscalIssuerBackfill;
    private final ContractLifecycleJob contractLifecycle;
    private final PromotionService promotionService;
    private final AccountMailOutboxService accountMail;
    private final PendingSignupService pendingSignup;
    // VERI*FACTU authorisation reconciliation & webhook event retention
    private final VerifactuAuthorizationService verifactuAuthorizations;
    private final VerifactuNotificationService verifactuNotifications;
    private final SandboxDeletionSyncJob sandboxDeletionSync;
    private final String secret;
    private final Clock clock;

    @Autowired
    public InternalJobsController(SandboxProvisioningJob sandboxProvisioning,
            VerifactuSubmissionJob verifactuSubmission,
            FiscalIssuerBackfillJob fiscalIssuerBackfill,
            ContractLifecycleJob contractLifecycle,
            @Autowired(required = false) PromotionService promotionService,
            @Autowired(required = false) AccountMailOutboxService accountMail,
            @Autowired(required = false) PendingSignupService pendingSignup,
            @Autowired(required = false) VerifactuAuthorizationService verifactuAuthorizations,
            @Autowired(required = false) VerifactuNotificationService verifactuNotifications,
            @Autowired(required = false) SandboxDeletionSyncJob sandboxDeletionSync,
            @Value("${fastbite.jobs.secret:}") String secret,
            Clock clock) {
        this.sandboxProvisioning = sandboxProvisioning;
        this.verifactuSubmission = verifactuSubmission;
        this.fiscalIssuerBackfill = fiscalIssuerBackfill;
        this.contractLifecycle = contractLifecycle;
        this.promotionService = promotionService;
        this.accountMail = accountMail;
        this.pendingSignup = pendingSignup;
        this.verifactuAuthorizations = verifactuAuthorizations;
        this.verifactuNotifications = verifactuNotifications;
        this.sandboxDeletionSync = sandboxDeletionSync;
        this.secret = secret == null || secret.isBlank() ? null : secret;
        this.clock = clock;
        if (this.secret != null && this.secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("FASTBITE_JOBS_SECRET must have at least " + MIN_SECRET_LENGTH
                    + " characters when set");
        }
    }

    public InternalJobsController(SandboxProvisioningJob sandboxProvisioning,
            VerifactuSubmissionJob verifactuSubmission,
            FiscalIssuerBackfillJob fiscalIssuerBackfill,
            ContractLifecycleJob contractLifecycle,
            PromotionService promotionService,
            AccountMailOutboxService accountMail,
            PendingSignupService pendingSignup,
            VerifactuAuthorizationService verifactuAuthorizations,
            VerifactuNotificationService verifactuNotifications,
            String secret,
            Clock clock) {
        this(sandboxProvisioning, verifactuSubmission, fiscalIssuerBackfill, contractLifecycle,
                promotionService, accountMail, pendingSignup, verifactuAuthorizations, verifactuNotifications,
                null, secret, clock);
    }

    public InternalJobsController(SandboxProvisioningJob sandboxProvisioning,
            VerifactuSubmissionJob verifactuSubmission,
            FiscalIssuerBackfillJob fiscalIssuerBackfill,
            String secret) {
        this(sandboxProvisioning, verifactuSubmission, fiscalIssuerBackfill, null, null, null, null, null, null, null, secret, Clock.systemUTC());
    }

    InternalJobsController(SandboxProvisioningJob sandboxProvisioning,
            VerifactuSubmissionJob verifactuSubmission,
            FiscalIssuerBackfillJob fiscalIssuerBackfill,
            String secret, Clock clock) {
        this(sandboxProvisioning, verifactuSubmission, fiscalIssuerBackfill, null, null, null, null, null, null, null, secret, clock);
    }

    @PostMapping("/internal/jobs/run")
    public ResponseEntity<JobRunSummary> run(
            @RequestHeader(value = SECRET_HEADER, required = false) String header) {
        if (secret == null) {
            return ResponseEntity.notFound().build();
        }
        if (!authorized(header)) {
            log.warn("Refused a jobs call without a valid {}", SECRET_HEADER);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Instant deadline = clock.instant().plus(VerifactuSubmissionJob.TIME_BUDGET);
        if (pendingSignup != null) pendingSignup.expire();
        if (accountMail != null) accountMail.run(5);
        var cleaned = accountMail == null ? null : accountMail.cleanup();
        int provisioned = sandboxProvisioning.run();
        if (sandboxDeletionSync != null) {
            sandboxDeletionSync.run();
        }
        VerifactuSubmissionJob.Summary verifactu = verifactuSubmission.run(deadline);
        int backfilled = fiscalIssuerBackfill.run(deadline);
        int contractActions = contractLifecycle != null ? contractLifecycle.run(deadline) : 0;
        int promotionsSwept = promotionService != null ? promotionService.sweep() : 0;
        // VERI*FACTU authorisation reconciliation & webhook event retention
        VerifactuAuthorizationService.ReconcileSummary verifactuAuth = verifactuAuthorizations != null
                ? verifactuAuthorizations.reconcile(deadline)
                : VerifactuAuthorizationService.ReconcileSummary.EMPTY;
        if (verifactuNotifications != null) {
            verifactuNotifications.sweep();
        }
        if (accountMail != null) {
            accountMail.run(10);
        }
        return ResponseEntity.ok(new JobRunSummary(
                provisioned,
                verifactu.submitted(),
                verifactu.locationsChecked(),
                verifactu.locationsRemaining(),
                backfilled,
                contractActions,
                promotionsSwept,
                cleaned == null ? 0 : cleaned.tokens(),
                cleaned == null ? 0 : cleaned.limits(),
                cleaned == null ? 0 : cleaned.outbox(),
                verifactuAuth.checked(),
                verifactuAuth.updated(),
                verifactuAuth.webhookEventsPurged()));
    }

    @GetMapping(value = "/internal/jobs/unsubscribe-token", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> issueUnsubscribeToken(
            @RequestHeader(value = SECRET_HEADER, required = false) String header,
            @RequestParam(value = "username", required = false) String username) {
        if (secret == null) {
            return ResponseEntity.notFound().build();
        }
        if (!authorized(header)) {
            log.warn("Refused an unsubscribe-token call without a valid {}", SECRET_HEADER);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return new MarketingUnsubscribeLink(secret, "http://localhost:8080")
                .issueToken(username.trim())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private boolean authorized(String header) {
        if (header == null) {
            return false;
        }
        return MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                header.getBytes(StandardCharsets.UTF_8));
    }
}
