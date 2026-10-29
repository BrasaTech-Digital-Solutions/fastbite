package es.brasatech.fastbite.application.tenant;

import java.time.Instant;
import java.util.Optional;

/** Pending signup and its mail intent are persisted in a single platform transaction. */
public interface PendingSignupPort {
    record Signup(String tenantId, String username, String fullName, String passwordHash, String plan,
                  String email, String language, String termsVersion, Instant acceptedAt, boolean marketing) { }
    record Pending(String id, Signup signup, Instant expiresAt) { }
    record Start(String pendingId, boolean pending) { }
    Start start(Signup signup, Instant now);
    boolean changeEmail(String pendingId, String email, Instant now);
    boolean resend(String pendingId, Instant now);
    Optional<Pending> forToken(String hash, Instant now);
    boolean alreadyVerified(String hash);
    boolean usernameAvailable(String username, Instant now);
    boolean isPending(String id, Instant now);
    int expire(Instant now);
    /** Consumes the token and creates both owner copies, location, billing and consent in one transaction. */
    boolean complete(String tokenHash, String pendingId, String tenantId, String username, Instant now);
}
