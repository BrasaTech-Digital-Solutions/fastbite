package es.brasatech.fastbite.application.tenant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TenantSignupService {
    private final TenantProvisionerPort provisioner;
    private final TenantRegistrationPort registration;
    private final TenantLifecyclePort lifecycle;
    private final NewLocationListener listener;

    public TenantSignupService(TenantProvisionerPort provisioner, TenantRegistrationPort registration,
            TenantLifecyclePort lifecycle) {
        this(provisioner, registration, lifecycle, NewLocationListener.NONE);
    }

    /** @param listener told of every location created, for example to set it up in the sandbox */
    @Autowired
    public TenantSignupService(TenantProvisionerPort provisioner, TenantRegistrationPort registration,
            TenantLifecyclePort lifecycle, java.util.Optional<NewLocationListener> listener) {
        this(provisioner, registration, lifecycle, listener.orElse(NewLocationListener.NONE));
    }

    public TenantSignupService(TenantProvisionerPort provisioner, TenantRegistrationPort registration,
            TenantLifecyclePort lifecycle, NewLocationListener listener) {
        this.provisioner = provisioner;
        this.registration = registration;
        this.lifecycle = lifecycle;
        this.listener = listener;
    }

    public void registerTenant(String tenantId, String username, String encodedPassword, String fullName) {
        registerTenant(tenantId, username, encodedPassword, fullName, "RESTAURANT");
    }

    public void registerTenant(String tenantId, String username, String encodedPassword, String fullName, String plan) {
        String tenant = TenantRegistrationRules.tenantId(tenantId);
        TenantRegistrationRules.owner(username);
        TenantRegistrationRules.plan(plan);
        if (encodedPassword == null || encodedPassword.isBlank() || encodedPassword.length() > 255
                || fullName == null || fullName.length() > 255) {
            throw new IllegalArgumentException("Invalid owner details.");
        }
        registration.requireAvailableUsername(tenant, username);
        lifecycle.register(tenant, "signup:" + tenant, username, TenantLifecyclePort.State.ACTIVE, () -> {
            provisioner.provisionTenant(tenant);
            registration.createOwner(tenant, username, encodedPassword, fullName, plan);
        });
        listener.locationCreated(tenant, username, plan);
    }

    public void registerAdditionalLocation(String tenantId, String ownerUsername, String plan) {
        registerAdditionalLocation(tenantId, ownerUsername, plan, "location:" + TenantRegistrationRules.tenantId(tenantId));
    }

    public void registerAdditionalLocation(String tenantId, String ownerUsername, String plan, String operationId) {
        String tenant = TenantRegistrationRules.tenantId(tenantId);
        TenantRegistrationRules.owner(ownerUsername);
        TenantRegistrationRules.plan(plan);
        registration.requireActiveOwner(ownerUsername);
        lifecycle.register(tenant, operationId, ownerUsername, TenantLifecyclePort.State.ACTIVE, () -> {
            provisioner.provisionTenant(tenant);
            registration.createLocation(tenant, ownerUsername, plan);
        });
        listener.locationCreated(tenant, ownerUsername, plan);
    }
}
