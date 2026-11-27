package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;

@Service
public class TenantSignupService {
    private final TenantProvisionerPort provisioner;
    private final TenantRegistrationPort registration;
    private final TenantLifecyclePort lifecycle;
    private final NewLocationListener listener;
    private final LegalAcceptancePort acceptances;
    private final LocationNameHoldPort nameHolds;
    private final Clock clock;

    public TenantSignupService(TenantProvisionerPort provisioner, TenantRegistrationPort registration,
            TenantLifecyclePort lifecycle) {
        this(provisioner, registration, lifecycle, NewLocationListener.NONE);
    }

    /** @param listener told of every location created, for example to set it up in the sandbox */
    @Autowired
    public TenantSignupService(TenantProvisionerPort provisioner, TenantRegistrationPort registration,
            TenantLifecyclePort lifecycle, Optional<NewLocationListener> listener,
            Optional<LegalAcceptancePort> acceptances,
            Optional<LocationNameHoldPort> nameHolds,
            Optional<Clock> clock) {
        this(provisioner, registration, lifecycle, listener.orElse(NewLocationListener.NONE),
                acceptances.orElse(LegalAcceptancePort.NONE),
                nameHolds.orElse(null),
                clock.orElse(Clock.systemUTC()));
    }

    public TenantSignupService(TenantProvisionerPort provisioner, TenantRegistrationPort registration,
            TenantLifecyclePort lifecycle, NewLocationListener listener) {
        this(provisioner, registration, lifecycle, listener, LegalAcceptancePort.NONE, null, Clock.systemUTC());
    }

    public TenantSignupService(TenantProvisionerPort provisioner, TenantRegistrationPort registration,
            TenantLifecyclePort lifecycle, NewLocationListener listener, LegalAcceptancePort acceptances) {
        this(provisioner, registration, lifecycle, listener, acceptances, null, Clock.systemUTC());
    }

    public TenantSignupService(TenantProvisionerPort provisioner, TenantRegistrationPort registration,
            TenantLifecyclePort lifecycle, NewLocationListener listener, LegalAcceptancePort acceptances,
            LocationNameHoldPort nameHolds, Clock clock) {
        this.provisioner = provisioner;
        this.registration = registration;
        this.lifecycle = lifecycle;
        this.listener = listener;
        this.acceptances = acceptances;
        this.nameHolds = nameHolds;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public void registerTenant(String tenantId, String username, String encodedPassword, String fullName) {
        registerTenant(tenantId, username, encodedPassword, fullName, "RESTAURANT");
    }

    public void registerTenant(String tenantId, String username, String encodedPassword, String fullName, String plan) {
        registerTenant(tenantId, username, encodedPassword, fullName, plan, null);
    }

    /**
     * Creates the owner and their first location. What they accepted is recorded in the same step as the owner,
     * so an owner never exists without the proof of their acceptance.
     */
    public void registerTenant(String tenantId, String username, String encodedPassword, String fullName, String plan,
            SignupConsents consents) {
        String tenant = TenantRegistrationRules.tenantId(tenantId);
        TenantRegistrationRules.owner(username);
        TenantRegistrationRules.plan(plan);
        if (encodedPassword == null || encodedPassword.isBlank() || encodedPassword.length() > 255
                || fullName == null || fullName.length() > 255) {
            throw new IllegalArgumentException("Invalid owner details.");
        }
        checkNameHold(tenant, username);
        registration.requireAvailableUsername(tenant, username);
        lifecycle.register(tenant, "signup:" + tenant, username, TenantLifecyclePort.State.ACTIVE, () -> {
            provisioner.provisionTenant(tenant);
            registration.createOwner(tenant, username, encodedPassword, fullName, plan);
            if (consents != null) {
                acceptances.record(username, LegalAcceptancePort.TERMS, consents.termsVersion(), consents.at());
                if (consents.marketing()) {
                    acceptances.record(username, LegalAcceptancePort.MARKETING, consents.termsVersion(), consents.at());
                }
            }
        });
        releaseNameHold(tenant);
        listener.locationCreated(tenant, username, plan);
    }

    public void registerAdditionalLocation(String tenantId, String ownerUsername, String plan) {
        registerAdditionalLocation(tenantId, ownerUsername, plan, "location:" + TenantRegistrationRules.tenantId(tenantId));
    }

    public void registerAdditionalLocation(String tenantId, String ownerUsername, String plan, String operationId) {
        String tenant = TenantRegistrationRules.tenantId(tenantId);
        TenantRegistrationRules.owner(ownerUsername);
        TenantRegistrationRules.plan(plan);
        checkNameHold(tenant, ownerUsername);
        registration.requireActiveOwner(ownerUsername);
        lifecycle.register(tenant, operationId, ownerUsername, TenantLifecyclePort.State.ACTIVE, () -> {
            provisioner.provisionTenant(tenant);
            registration.createLocation(tenant, ownerUsername, plan);
        });
        releaseNameHold(tenant);
        listener.locationCreated(tenant, ownerUsername, plan);
    }

    private void checkNameHold(String tenant, String ownerUsername) {
        if (nameHolds == null) {
            return;
        }
        nameHolds.findActive(tenant, clock.instant()).ifPresent(hold -> {
            if (!hold.owner().equalsIgnoreCase(ownerUsername)) {
                throw new LocalizedRejection("location.error.name_held", "This name is not available.");
            }
        });
    }

    private void releaseNameHold(String tenant) {
        if (nameHolds != null) {
            nameHolds.release(tenant);
        }
    }
}
