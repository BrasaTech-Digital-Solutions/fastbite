package es.brasatech.fastbite.application.platform;

import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.tenant.TenantRegistrationPort;
import es.brasatech.fastbite.application.tenant.TenantRegistrationRules;
import es.brasatech.fastbite.application.tenant.TenantSignupService;
import org.springframework.stereotype.Service;

/**
 * The sandbox's side of setting up a location production created: the same owner and location, so
 * the owner finds it ready when they enter. Doing it again changes nothing.
 *
 * <p>The owner gets no password here: a linked sandbox is entered through production. Sandbox
 * locations have no trial or billing of their own (plan FREE); access follows production.
 */
@Service
public class LinkedLocationService {

    /** Sandbox locations are never billed: production holds the subscription. */
    static final String SANDBOX_PLAN = "FREE";

    private final TenantLocationService locations;
    private final TenantRegistrationPort registration;
    private final TenantSignupService signup;

    public LinkedLocationService(TenantLocationService locations, TenantRegistrationPort registration,
            TenantSignupService signup) {
        this.locations = locations;
        this.registration = registration;
        this.signup = signup;
    }

    /**
     * @param unusablePassword an encoded password nobody knows, for an owner new to the sandbox
     * @throws IllegalArgumentException if the location belongs to another owner here
     */
    public void receive(SandboxLinkPort.LinkedLocation location, String unusablePassword) {
        String tenant = TenantRegistrationRules.tenantId(location.tenantId());
        String owner = location.ownerUsername();
        TenantRegistrationRules.owner(owner);
        var existing = locations.getLocation(tenant);
        if (existing.isPresent()) {
            if (!owner.equals(existing.get().ownerUsername())) {
                throw new IllegalArgumentException("Location " + tenant + " belongs to another owner in the sandbox");
            }
            return;
        }
        if (ownerExists(owner)) {
            signup.registerAdditionalLocation(tenant, owner, SANDBOX_PLAN, "link:" + tenant);
        } else {
            String name = location.ownerName() == null || location.ownerName().isBlank() ? owner : location.ownerName();
            signup.registerTenant(tenant, owner, unusablePassword, name, SANDBOX_PLAN);
        }
    }

    private boolean ownerExists(String owner) {
        try {
            registration.requireActiveOwner(owner);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
