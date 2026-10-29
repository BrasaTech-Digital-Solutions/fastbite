package es.brasatech.fastbite.application.office;

import es.brasatech.fastbite.application.mail.AccountMailOutboxService;
import es.brasatech.fastbite.application.tenant.PendingSignupService;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

@Service
public class OwnerEmailService {
    private final OwnerEmailPort owners;
    private final PendingSignupService signup;
    private final AccountMailOutboxService mail;
    private final Clock clock;
    public OwnerEmailService(OwnerEmailPort owners, PendingSignupService signup, AccountMailOutboxService mail, Clock clock) {
        this.owners = owners; this.signup = signup; this.mail = mail; this.clock = clock;
    }
    public java.util.Optional<OwnerEmailPort.Status> status(String username) { return owners.status(username); }
    public void request(String username, String address, String language) {
        noOuterTransaction();
        String email = signup.normalizeEmail(address);
        if (owners.request(username, email, language, clock.instant())) deliver(username);
    }
    public void resend(String username) { noOuterTransaction(); if (owners.resend(username, clock.instant())) deliver(username); }
    private void deliver(String username) {
        owners.status(username).ifPresent(owner -> { try { mail.runForSignup(owner.id()); } catch (RuntimeException ignored) { } });
    }
    private static void noOuterTransaction() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Owner email operations must not run inside an outer transaction");
    }
    public boolean verify(String token) {
        noOuterTransaction();
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        try {
            var ownerId = owners.verify(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))), clock.instant());
            ownerId.ifPresent(id -> { try { mail.runForSignup(id); } catch (RuntimeException ignored) { } });
            return ownerId.isPresent();
        }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
