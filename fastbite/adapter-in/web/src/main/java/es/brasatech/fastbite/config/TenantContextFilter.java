package es.brasatech.fastbite.config;

import java.util.Set;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resolves the tenant once per request and exposes it to the rest of the request
 * (persistence through {@link TenantContext}, security and views through request attributes).
 * An address that names a restaurant this environment doesn't have (path prefix or host) gets a 404 before any data
 * is read; one only hinted at by the Referer or {@code ?tenantId=} is ignored instead.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    /** Request attribute telling the error page that the restaurant in the address doesn't exist. */
    public static final String UNKNOWN_RESTAURANT = "unknownRestaurant";

    // These guest routes read restaurant data, even on their host-based (unprefixed) forms.
    private static final Set<String> RESTAURANT_GUEST_PATHS = Set.of(
            "/menu", "/allergens", "/api/calculate-cart", "/api/calculate-confirmation",
            "/select-payment", "/api/create-order", "/api/order-status", "/api/order-status/live",
            "/order-confirmation", "/api/stripe/create-checkout-session");

    private final TenantRoutingResolver tenantResolver;

    public TenantContextFilter(TenantRoutingResolver tenantResolver) {
        this.tenantResolver = tenantResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TenantRoutingResolver.ResolvedTenant tenant = tenantResolver.resolve(request);
        if (tenant != null && !tenantResolver.exists(tenant.id())) {
            if (tenant.fromHost() || tenant.id().equals(TenantRoutingResolver.firstSegment(
                    request.getRequestURI().substring(request.getContextPath().length())))) {
                request.setAttribute(UNKNOWN_RESTAURANT, Boolean.TRUE);
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            tenant = null;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (tenant == null && RESTAURANT_GUEST_PATHS.contains(path)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (tenant != null) {
            TenantContext.setCurrentTenant(tenant.id());
            request.setAttribute("tenantId", tenant.id());
            request.setAttribute(TenantRoutingResolver.TENANT_URL_PREFIX, tenant.urlPrefix());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
