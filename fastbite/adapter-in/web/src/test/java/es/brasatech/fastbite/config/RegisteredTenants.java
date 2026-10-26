package es.brasatech.fastbite.config;

import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.domain.tenant.TenantLocation;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * For web tests that mock {@link TenantLocationService}: every restaurant they address exists, so
 * {@link TenantContextFilter} lets the request through instead of answering 404.
 */
public final class RegisteredTenants {
    private RegisteredTenants() { }

    public static void all(TenantLocationService locations) {
        lenient().when(locations.getLocation(anyString())).thenAnswer(call ->
                Optional.of(new TenantLocation(null, "owner", call.getArgument(0), "FREE", null)));
    }
}
