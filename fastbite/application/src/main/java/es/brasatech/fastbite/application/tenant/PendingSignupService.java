package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.application.mail.AccountMailOutboxService;
import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

@Service
public class PendingSignupService {
    private final PendingSignupPort pending;
    private final TenantProvisionerPort provisioner;
    private final TenantLifecyclePort lifecycle;
    private final AccountMailOutboxService mail;
    private final NewLocationListener listener;
    private final LocationNameHoldPort nameHolds;
    private final Clock clock;

    @Autowired
    public PendingSignupService(PendingSignupPort pending, TenantProvisionerPort provisioner, TenantLifecyclePort lifecycle,
            AccountMailOutboxService mail, Optional<NewLocationListener> listener,
            Optional<LocationNameHoldPort> nameHolds, Clock clock) {
        this.pending = pending; this.provisioner = provisioner; this.lifecycle = lifecycle;
        this.mail = mail; this.listener = listener.orElse(NewLocationListener.NONE);
        this.nameHolds = nameHolds.orElse(null); this.clock = clock;
    }

    public PendingSignupService(PendingSignupPort pending, TenantProvisionerPort provisioner, TenantLifecyclePort lifecycle,
            AccountMailOutboxService mail, Optional<NewLocationListener> listener, Clock clock) {
        this(pending, provisioner, lifecycle, mail, listener, Optional.empty(), clock);
    }
    public PendingSignupPort.Start start(PendingSignupPort.Signup input) {
        noOuterTransaction();
        String tenant = TenantRegistrationRules.tenantId(input.tenantId());
        TenantRegistrationRules.owner(input.username());
        TenantRegistrationRules.plan(input.plan());
        if (input.username().contains("@")) throw new LocalizedRejection("signup.error.username", "Choose a username without @.");
        if (input.fullName() == null || input.fullName().isBlank() || input.fullName().length() > 255
                || input.passwordHash() == null || input.passwordHash().isBlank() || input.passwordHash().length() > 255
                || input.termsVersion() == null || input.termsVersion().isBlank()) throw new IllegalArgumentException("Invalid signup details.");
        String email = email(input.email());
        if (nameHolds != null) {
            nameHolds.findActive(tenant, clock.instant()).ifPresent(hold -> {
                if (!hold.owner().equalsIgnoreCase(input.username())) {
                    throw new LocalizedRejection("location.error.name_held", "This name is not available.");
                }
            });
        }
        String language = switch (input.language() == null ? "en" : input.language().toLowerCase(Locale.ROOT)) {
            case "es" -> "es"; case "pt" -> "pt"; default -> "en";
        };
        var normalized = new PendingSignupPort.Signup(tenant, input.username(), input.fullName(), input.passwordHash(), input.plan(), email,
                language, input.termsVersion(), input.acceptedAt(), input.marketing());
        var result = pending.start(normalized, clock.instant());
        try { mail.runForSignup(result.pendingId()); } catch (RuntimeException ignored) { /* Durable intent remains for the job. */ }
        return result;
    }
    public String normalizeEmail(String value) { return email(value); }
    public void changeEmail(String id, String email) {
        noOuterTransaction();
        if (pending.changeEmail(id, email(email), clock.instant())) try { mail.runForSignup(id); } catch (RuntimeException ignored) { }
    }
    public void resend(String id) {
        noOuterTransaction();
        if (pending.resend(id, clock.instant())) try { mail.runForSignup(id); } catch (RuntimeException ignored) { }
    }
    public boolean verify(String token) {
        noOuterTransaction();
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        if (pending.alreadyVerified(hash)) return true;
        var registration = pending.forToken(hash, clock.instant());
        if (registration.isEmpty()) return false;
        var item = registration.get();
        var data = item.signup();
        lifecycle.register(data.tenantId(), "signup:" + data.tenantId(), data.username(), TenantLifecyclePort.State.ACTIVE, () -> {
            provisioner.provisionTenant(data.tenantId());
            if (!pending.complete(hash, item.id(), data.tenantId(), data.username(), clock.instant())) throw new IllegalStateException("Signup verification expired.");
        });
        if (!pending.alreadyVerified(hash)) return false;
        if (nameHolds != null) {
            nameHolds.release(data.tenantId());
        }
        listener.locationCreated(data.tenantId(), data.username(), data.plan());
        return true;
    }
    public java.util.Optional<PendingSignupPort.Pending> forBrowser(String id) { return pending.forBrowser(id, clock.instant()); }
    public boolean isPending(String id) { return pending.isPending(id, clock.instant()); }
    public boolean usernameAvailable(String username) {
        if (username == null || !username.matches("[a-zA-Z0-9_.-]{1,100}")) return false;
        return pending.usernameAvailable(username, clock.instant());
    }
    public int expire() { return pending.expire(clock.instant()); }
    private static String email(String value) {
        String email = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new LocalizedRejection("signup.error.email", "Enter a valid email address.");
        return email;
    }
    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Signup must not run inside an outer transaction");
    }
}
