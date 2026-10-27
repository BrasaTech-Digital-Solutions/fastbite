package es.brasatech.fastbite.application.platform;

import es.brasatech.fastbite.application.tenant.SubscriptionService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Production's side of "Enter sandbox": it makes sure the sandbox has the location, then hands the
 * owner a signed single-use link into it. Only an owner whose location may trade gets one, so a
 * subscription that ends closes the sandbox too.
 */
@Service
public class SandboxEntryService {

    /** Where the sandbox takes the link. */
    public static final String ENTER_PATH = "/link/enter";

    public enum State {
        /** Follow the link. */
        READY,
        /** The sandbox does not have the location yet: it is down or being updated. */
        PREPARING,
        /** The location's trial or subscription has ended. */
        SUBSCRIPTION_ENDED
    }

    /** @param url the sign-in link, when ready */
    public record Entry(State state, String url) {
    }

    private final SandboxProvisioningService provisioning;
    private final TenantLocationService locations;
    private final SubscriptionService subscriptions;
    private final PlatformLink link;
    private final String version;
    private final Clock clock;

    @Autowired
    public SandboxEntryService(SandboxProvisioningService provisioning, TenantLocationService locations,
            SubscriptionService subscriptions, PlatformLink link, @Value("${fastbite.version:dev}") String version) {
        this(provisioning, locations, subscriptions, link, version, Clock.systemUTC());
    }

    SandboxEntryService(SandboxProvisioningService provisioning, TenantLocationService locations,
            SubscriptionService subscriptions, PlatformLink link, String version, Clock clock) {
        this.provisioning = provisioning;
        this.locations = locations;
        this.subscriptions = subscriptions;
        this.link = link;
        this.version = version;
        this.clock = clock;
    }

    /** Whether this environment has a sandbox to send owners to. */
    public boolean available() {
        return provisioning.active();
    }

    /** @throws IllegalArgumentException when the location is not the owner's */
    public Entry enter(String owner, String tenantId) {
        if (!available()) {
            throw new IllegalStateException("No sandbox is linked");
        }
        if (!locations.isOwnerOf(owner, tenantId)) {
            throw new IllegalArgumentException("Location " + tenantId + " is not yours");
        }
        if (!subscriptions.canTakeOrders(tenantId) && !subscriptions.isFreePlan(tenantId)) {
            return new Entry(State.SUBSCRIPTION_ENDED, null);
        }
        if (!provisioning.ensure(tenantId, owner)) {
            return new Entry(State.PREPARING, null);
        }
        String token = SandboxSignInLink.issue(link.secret(), owner, tenantId, version, clock.instant());
        return new Entry(State.READY, link.url() + ENTER_PATH + "?token=" + token);
    }
}
