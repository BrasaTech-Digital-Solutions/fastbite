package es.brasatech.fastbite.application.office;

import es.brasatech.fastbite.application.mail.AccountMailOutboxService;
import es.brasatech.fastbite.application.tenant.PendingSignupService;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class StaffAccountService {
    private final StaffAccountPort staff;
    private final PendingSignupService signup;
    private final AccountMailOutboxService mail;
    private final Clock clock;
    public StaffAccountService(StaffAccountPort staff, PendingSignupService signup, AccountMailOutboxService mail, Clock clock) {
        this.staff = staff; this.signup = signup; this.mail = mail; this.clock = clock;
    }
    public List<StaffAccountPort.Staff> list(String actor, boolean owner, String tenant) { return staff.list(actor, owner, tenant); }
    public Optional<StaffAccountPort.Staff> find(String actor, boolean owner, String tenant, String id) { return staff.find(actor, owner, tenant, id); }
    public StaffAccountPort.Staff password(String actor, boolean owner, String tenant, String id, String hash) { return staff.setPassword(actor, owner, tenant, id, hash); }
    public void email(String actor, boolean owner, String tenant, String id, String email, String language) {
        outsideTransaction();
        var account = staff.setEmail(actor, owner, tenant, id, signup.normalizeEmail(email), language, clock.instant()); deliver(account.id());
    }
    public void reset(String actor, boolean owner, String tenant, String id) { outsideTransaction(); staff.enqueueReset(actor, owner, tenant, id, clock.instant()).ifPresent(s -> deliver(s.id())); }
    private static void outsideTransaction() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Staff account email must run outside a transaction");
    }
    private void deliver(String id) { try { mail.runForSignup(id); } catch (RuntimeException ignored) { } }
    public boolean verify(String token) {
        outsideTransaction();
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        try { return staff.verifyEmail(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))), clock.instant()); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
