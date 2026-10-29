package es.brasatech.fastbite.application.office;

import es.brasatech.fastbite.application.mail.AccountMailOutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class PasswordRecoveryService {
    private final PasswordRecoveryPort recovery;
    private final AccountMailOutboxService mail;
    private final Clock clock;
    public PasswordRecoveryService(PasswordRecoveryPort recovery, AccountMailOutboxService mail, Clock clock) {
        this.recovery = recovery; this.mail = mail; this.clock = clock;
    }
    public void request(String identifier, String tenantId) {
        outsideTransaction();
        if (identifier == null || identifier.isBlank() || identifier.length() > 254) return;
        recovery.enqueue(identifier.strip(), tenantId, clock.instant()).ifPresent(account -> {
            try { mail.runForSignup(account.id()); } catch (RuntimeException ignored) { }
        });
    }
    public boolean valid(String token) { outsideTransaction(); return hash(token).map(h -> recovery.valid(h, clock.instant())).orElse(false); }
    public Optional<PasswordRecoveryPort.Account> reset(String token, String passwordHash) {
        outsideTransaction(); return hash(token).flatMap(h -> recovery.consume(h, passwordHash, clock.instant()));
    }
    private static Optional<String> hash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        try { return Optional.of(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Password recovery must run outside a transaction");
    }
}
