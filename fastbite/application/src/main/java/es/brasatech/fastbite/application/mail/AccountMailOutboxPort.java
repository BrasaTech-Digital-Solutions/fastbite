package es.brasatech.fastbite.application.mail;

import java.time.Instant;
import java.util.Optional;

/** Short database operations; implementations must not perform SMTP. */
public interface AccountMailOutboxPort {
    record Cleanup(int tokens, int limits, int outbox) { }
    Cleanup cleanup(Instant now);
    record Claim(String id, String claimId, String subjectId, int attempts, String purpose) {
        public Claim(String id, String claimId, String subjectId, int attempts) { this(id, claimId, subjectId, attempts, "OWNER_SETUP"); }
    }
    record Recipient(String email, String username, String language) { }
    Optional<Claim> claim(Instant now);
    Optional<Claim> claimForSubject(String subjectId, Instant now);
    Optional<Recipient> issue(Claim claim, String hash, Instant now);
    default Optional<Recipient> issueOwnerSetup(Claim claim, String hash, Instant now) { return issue(claim, hash, now); }
    void sent(Claim claim, Instant now);
    String failed(Claim claim, Instant now);
    /**
     * Enqueues a VERI*FACTU state-transition email ({@code VERIFACTU_ON_HOLD} or
     * {@code VERIFACTU_OPENED}) once per transition for the given production location.
     *
     * @return {@code true} when a new outbox row was queued
     */
    default boolean enqueueVerifactuNotification(String tenantId, String purpose, Instant now) {
        return false;
    }
}
