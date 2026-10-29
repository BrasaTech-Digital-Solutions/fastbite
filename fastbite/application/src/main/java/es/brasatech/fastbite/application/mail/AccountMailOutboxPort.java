package es.brasatech.fastbite.application.mail;

import java.time.Instant;
import java.util.Optional;

/** Short database operations; implementations must not perform SMTP. */
public interface AccountMailOutboxPort {
    record Claim(String id, String claimId, String subjectId, int attempts) { }
    record Recipient(String email, String username, String language) { }
    Optional<Claim> claim(Instant now);
    Optional<Claim> claimForSubject(String subjectId, Instant now);
    Optional<Recipient> issueOwnerSetup(Claim claim, String hash, Instant now);
    void sent(Claim claim, Instant now);
    String failed(Claim claim, Instant now);
}
