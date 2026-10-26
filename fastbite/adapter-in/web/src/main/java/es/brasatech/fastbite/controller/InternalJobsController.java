package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.service.FiscalIssuerBackfillJob;
import es.brasatech.fastbite.service.SandboxProvisioningJob;
import es.brasatech.fastbite.service.VerifactuSubmissionJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
            int fiscalIssuersBackfilled) {
    }

    private final SandboxProvisioningJob sandboxProvisioning;
    private final VerifactuSubmissionJob verifactuSubmission;
    private final FiscalIssuerBackfillJob fiscalIssuerBackfill;
    private final String secret;
    private final Clock clock;

    @Autowired
    public InternalJobsController(SandboxProvisioningJob sandboxProvisioning,
            VerifactuSubmissionJob verifactuSubmission,
            FiscalIssuerBackfillJob fiscalIssuerBackfill,
            @Value("${fastbite.jobs.secret:}") String secret) {
        this(sandboxProvisioning, verifactuSubmission, fiscalIssuerBackfill, secret, Clock.systemUTC());
    }

    InternalJobsController(SandboxProvisioningJob sandboxProvisioning,
            VerifactuSubmissionJob verifactuSubmission,
            FiscalIssuerBackfillJob fiscalIssuerBackfill,
            String secret, Clock clock) {
        this.sandboxProvisioning = sandboxProvisioning;
        this.verifactuSubmission = verifactuSubmission;
        this.fiscalIssuerBackfill = fiscalIssuerBackfill;
        this.secret = secret == null || secret.isBlank() ? null : secret;
        this.clock = clock;
        if (this.secret != null && this.secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("FASTBITE_JOBS_SECRET must have at least " + MIN_SECRET_LENGTH
                    + " characters when set");
        }
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
        int provisioned = sandboxProvisioning.run();
        VerifactuSubmissionJob.Summary verifactu = verifactuSubmission.run(deadline);
        int backfilled = fiscalIssuerBackfill.run(deadline);
        return ResponseEntity.ok(new JobRunSummary(
                provisioned,
                verifactu.submitted(),
                verifactu.locationsChecked(),
                verifactu.locationsRemaining(),
                backfilled));
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
