package es.brasatech.fastbite.application.office;

import java.time.Instant;
import java.util.Optional;

public interface OwnerEmailPort {
    record Status(String id, String email, String pendingEmail, boolean verified) { }
    Optional<Status> status(String username);
    boolean request(String username, String email, String language, Instant now);
    boolean resend(String username, Instant now);
    Optional<String> verify(String hash, Instant now);
}
