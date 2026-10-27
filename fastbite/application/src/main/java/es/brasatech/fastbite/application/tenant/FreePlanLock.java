package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * On the Free plan in production, online takeaway orders stay enabled in every channel FastBite
 * offers. The sandbox stays unlocked so owners can practise freely.
 */
@Component
public class FreePlanLock {

    private final PlatformEnvironment environment;
    private final TenantLocationService locations;

    public FreePlanLock(PlatformEnvironment environment, TenantLocationService locations) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.locations = Objects.requireNonNull(locations, "locations");
    }

    public boolean locked(String tenant) {
        return environment.production() && tenant != null
                && locations.getLocation(tenant).map(loc -> "FREE".equalsIgnoreCase(loc.plan())).orElse(false);
    }
}
