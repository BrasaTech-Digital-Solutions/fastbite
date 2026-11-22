package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import org.springframework.beans.factory.ObjectProvider;

/** What every guest page shows at its top: the restaurant's name, not FastBite's. */
final class GuestPages {

    static final String RESTAURANT_NAME = "restaurantName";

    private GuestPages() {
    }

    /** The name the restaurant gives guests, else its location id; null when there is neither. */
    static String restaurantName(ObjectProvider<FiscalProfileService> profiles) {
        return restaurantName(profiles.getIfAvailable());
    }

    /** The same name for a page that always has the fiscal profiles, such as the counter's top bar. */
    static String restaurantName(FiscalProfileService service) {
        FiscalProfile profile = service != null ? service.get() : null;
        String name = profile != null ? profile.displayName() : null;
        return name != null && !name.isBlank() ? name : TenantContext.getCurrentTenant();
    }
}
