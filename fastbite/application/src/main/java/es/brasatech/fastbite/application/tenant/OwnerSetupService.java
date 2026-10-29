package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.application.mail.AccountMailOutboxService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class OwnerSetupService {
    private final OwnerSetupPort persistence;
    private final TenantLifecyclePort lifecycle;
    private final TenantProvisionerPort provisioner;
    private final AccountMailOutboxService mail;
    private final String publicUrl;
    private final NewLocationListener listener;

    public OwnerSetupService(OwnerSetupPort persistence, TenantProvisionerPort provisioner,
            AccountMailOutboxService mail, TenantLifecyclePort lifecycle, String publicUrl) {
        this(persistence, provisioner, mail, lifecycle, publicUrl, NewLocationListener.NONE);
    }

    /** @param listener told of the location the paid checkout created, for example to set it up in the sandbox */
    @Autowired
    public OwnerSetupService(OwnerSetupPort persistence, TenantProvisionerPort provisioner,
            AccountMailOutboxService mail, TenantLifecyclePort lifecycle, @Value("${fastbite.public-url:http://localhost:8080}") String publicUrl,
            java.util.Optional<NewLocationListener> listener) {
        this(persistence, provisioner, mail, lifecycle, publicUrl, listener.orElse(NewLocationListener.NONE));
    }

    public OwnerSetupService(OwnerSetupPort persistence, TenantProvisionerPort provisioner,
            AccountMailOutboxService mail, TenantLifecyclePort lifecycle, String publicUrl, NewLocationListener listener) {
        this.listener = listener;
        this.persistence = persistence;
        this.lifecycle = lifecycle;
        this.provisioner = provisioner;
        this.mail = mail;
        URI uri = URI.create(publicUrl);
        boolean local = "http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || (uri.getHost() != null && uri.getHost().endsWith(".localhost")));
        if (uri.getHost() == null || (!"https".equals(uri.getScheme()) && !local)
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("fastbite.public-url must be an HTTPS origin (HTTP is allowed on localhost)");
        }
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    public void invite(OwnerSetupPort.Invitation input) {
        // Lifecycle adapters commit independently, so an outer rollback could not undo them.
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Owner setup must not run inside an outer transaction");
        String tenant = TenantRegistrationRules.tenantId(input.tenantId());
        // PostgreSQL identifiers are limited to 63 bytes, including the seven-byte tenant_ prefix.
        if (input.checkoutId() == null || input.checkoutId().isBlank() || input.checkoutId().length() > 255
                || input.username() == null || !input.username().matches("[a-zA-Z0-9_.@-]{1,100}")
                || input.email() == null || input.email().length() > 254
                || !input.email().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                || input.fullName() == null || input.fullName().length() > 255
                || input.plan() == null || input.plan().length() > 255) {
            throw new IllegalArgumentException("Invalid owner setup details");
        }
        var invitation = new OwnerSetupPort.Invitation(input.checkoutId(), tenant, input.username(),
                input.fullName(), input.email(), input.plan());
        lifecycle.register(tenant, "setup:" + input.checkoutId(), invitation.username(),
                TenantLifecyclePort.State.AWAITING_OWNER, () -> {
                    provisioner.provisionTenant(tenant);
                    persistence.prepareQueued(invitation, Instant.now());
                });
        // Registration and its email intent are committed before delivery is attempted.
        try { mail.runForSetup(invitation.checkoutId()); } catch (RuntimeException ignored) { /* The jobs endpoint retries the durable intent. */ }
        listener.locationCreated(tenant, invitation.username(), invitation.plan());
    }

    public boolean isValid(String token) {
        return wellFormed(token) && persistence.isValid(hash(token), Instant.now());
    }

    public boolean complete(String token, String encodedPassword) {
        return wellFormed(token) && persistence.complete(hash(token), encodedPassword, Instant.now());
    }

    private static boolean wellFormed(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}");
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
