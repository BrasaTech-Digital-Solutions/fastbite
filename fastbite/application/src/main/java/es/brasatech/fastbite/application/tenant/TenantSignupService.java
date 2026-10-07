package es.brasatech.fastbite.application.tenant;

import org.springframework.stereotype.Service;

@Service
public class TenantSignupService {
    private final TenantProvisionerPort provisioner;
    private final TenantRegistrationPort registration;
    private final TenantLifecyclePort lifecycle;

    public TenantSignupService(TenantProvisionerPort provisioner, TenantRegistrationPort registration,
            TenantLifecyclePort lifecycle) {
        this.provisioner = provisioner;
        this.registration = registration;
        this.lifecycle = lifecycle;
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
    }
}
