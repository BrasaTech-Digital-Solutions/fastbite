package es.brasatech.fastbite;

import es.brasatech.fastbite.application.mail.AccountMailOutboxPort;
import es.brasatech.fastbite.application.tenant.PendingSignupPort;
import es.brasatech.fastbite.application.tenant.PendingSignupService;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** Setup for integration tests whose subject is outside the signup flow. */
final class VerifiedSignupFixture {
    static void create(PendingSignupPort pendingPort, PendingSignupService signup, AccountMailOutboxPort outbox, PasswordEncoder passwords,
            String tenant, String username, String name, String password) throws Exception {
        Instant now = Instant.now();
        var pending = pendingPort.start(new PendingSignupPort.Signup(tenant, username, name, passwords.encode(password), "RESTAURANT",
                username.toLowerCase(java.util.Locale.ROOT) + "@example.test", "en", "test-version", now, false), now);
        var claim = outbox.claimForSubject(pending.pendingId(), Instant.now()).orElseThrow();
        byte[] random = UUID.randomUUID().toString().replace("-", "").getBytes(StandardCharsets.UTF_8);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        outbox.issue(claim, hash, Instant.now()).orElseThrow();
        outbox.sent(claim, Instant.now());
        if (!signup.verify(token)) throw new AssertionError("Fixture signup did not verify");
    }
}
