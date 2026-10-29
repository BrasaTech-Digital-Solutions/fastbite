package es.brasatech.fastbite.application.mail;

import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;

/** Delivers committed account-email intents; SMTP always runs outside database transactions. */
@Service
public class AccountMailOutboxService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AccountMailOutboxService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AccountMailOutboxPort outbox;
    private final OwnerSetupMailPort mail;
    private final PlatformEnvironment environment;
    private final Clock clock;
    private final String origin;
    public AccountMailOutboxService(AccountMailOutboxPort outbox, OwnerSetupMailPort mail, PlatformEnvironment environment,
            Clock clock, @Value("${fastbite.public-url:http://localhost:8080}") String origin) {
        this.outbox = outbox; this.mail = mail; this.environment = environment; this.clock = clock;
        this.origin = origin.replaceAll("/+$", "");
    }
    public int run(int limit) {
        if (!deliveryAllowed()) return 0;
        int sent = 0;
        for (int i = 0; i < limit; i++) {
            var claim = outbox.claim(clock.instant());
            if (claim.isEmpty()) break;
            if (deliver(claim.get())) sent++;
        }
        return sent;
    }
    public void runForSetup(String checkoutId) {
        if (!deliveryAllowed()) return;
        outbox.claimForSubject(checkoutId, clock.instant()).ifPresent(this::deliver);
    }
    public void runForSignup(String signupId) {
        if (!deliveryAllowed()) return;
        outbox.claimForSubject(signupId, clock.instant()).ifPresent(this::deliver);
    }
    private boolean deliveryAllowed() {
        return !environment.sandbox() && !TransactionSynchronizationManager.isActualTransactionActive();
    }
    private boolean deliver(AccountMailOutboxPort.Claim item) {
        try {
            byte[] random = new byte[32]; RANDOM.nextBytes(random);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
            var recipient = outbox.issue(item, hash, clock.instant());
            if (recipient.isEmpty()) return false;
            var target = recipient.get();
            switch (item.purpose()) {
                case "OWNER_SETUP" -> mail.sendSetupLink(target.email(), target.username(), origin + "/set-password?token=" + token, target.language());
                case "SIGNUP_VERIFY" -> mail.sendSignupVerification(target.email(), target.username(), origin + "/verify-email?token=" + token, target.language());
                case "SIGNUP_NOTICE" -> mail.sendSignupNotice(target.email(), target.language());
                default -> throw new IllegalStateException("Unknown account email purpose");
            }
            outbox.sent(item, clock.instant());
            return true;
        } catch (Exception error) {
            // Do not log exception text: mail transports may include recipient or message contents.
            String state = "UNKNOWN";
            try {
                state = outbox.failed(item, clock.instant());
            } finally {
                log.warn("Account email delivery failed: outbox={} purpose={} attempt={} error={} state={}",
                        item.id(), item.purpose(), item.attempts(), error.getClass().getSimpleName(), state);
            }
            return false;
        }
    }
}
