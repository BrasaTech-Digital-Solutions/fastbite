package es.brasatech.fastbite.config;

import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.security.TenantUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Resolves the tenant once per request and exposes it to the rest of the request
 * (persistence through {@link TenantContext}, security and views through request attributes).
 * An address that names a restaurant this environment doesn't have (path prefix or host) gets a 404/410 before any data
 * is read; one only hinted at by the Referer or {@code ?tenantId=} is ignored instead.
 * Staff sessions bound to a deleted or closed location are signed out immediately on their next request.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    /** Request attribute telling the error page that the restaurant in the address doesn't exist or is closed. */
    public static final String UNKNOWN_RESTAURANT = "unknownRestaurant";

    // These guest routes read restaurant data, even on their host-based (unprefixed) forms.
    private static final Set<String> RESTAURANT_GUEST_PATHS = Set.of(
            "/menu", "/allergens", "/api/calculate-cart", "/api/calculate-confirmation",
            "/select-payment", "/api/create-order", "/api/order-status", "/api/order-status/live",
            "/order-confirmation", "/api/stripe/create-checkout-session");

    // Second-segment routes that only exist under a restaurant prefix (guards non-slug prefixes like /foo.bar/menu).
    private static final Set<String> TENANT_SUBPATHS = Set.of(
            "menu", "allergens", "counter", "dashboard", "backoffice", "login", "logout",
            "ticket", "privacy", "select-payment", "order-confirmation", "api");

    private final TenantRoutingResolver tenantResolver;

    public TenantContextFilter(TenantRoutingResolver tenantResolver) {
        this.tenantResolver = tenantResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (invalidateStaffSessionIfTenantGone(request, response)) {
            return;
        }
        TenantRoutingResolver.ResolvedTenant tenant = tenantResolver.resolve(request);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (tenant != null && !tenantResolver.exists(tenant.id())) {
            if (tenant.fromHost() || tenant.id().equals(TenantRoutingResolver.firstSegment(path))) {
                sendUnavailableRestaurant(request, response, HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            tenant = null;
        }
        if (tenant == null) {
            String first = TenantRoutingResolver.firstSegment(path);
            if (!first.isEmpty() && !TenantRoutingResolver.isReserved(first) && TENANT_SUBPATHS.contains(secondSegment(path))) {
                sendUnavailableRestaurant(request, response, HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            if (RESTAURANT_GUEST_PATHS.contains(path)) {
                sendUnavailableRestaurant(request, response, HttpServletResponse.SC_NOT_FOUND);
                return;
            }
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

    private boolean invalidateStaffSessionIfTenantGone(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals("/error") || path.equals("/login") || path.equals("/logout")
                || path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/")) {
            return false;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }
        Object attr = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(attr instanceof SecurityContext sc)) {
            return false;
        }
        Authentication auth = sc.getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof TenantUser user) || user.isPlatformAccount()) {
            return false;
        }
        String homeTenant = user.getHomeTenantId();
        if (homeTenant != null && tenantResolver.exists(homeTenant)) {
            return false;
        }
        session.invalidate();
        SecurityContextHolder.clearContext();
        if (isApiOrJsonRequest(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"session_invalidated\"}");
        } else {
            response.sendRedirect(request.getContextPath() + "/login");
        }
        return true;
    }

    private static void sendUnavailableRestaurant(HttpServletRequest request, HttpServletResponse response, int status)
            throws IOException {
        request.setAttribute(UNKNOWN_RESTAURANT, Boolean.TRUE);
        if (isApiOrJsonRequest(request)) {
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"restaurant_unavailable\",\"message\":\"This restaurant is no longer available\"}");
            return;
        }
        response.sendError(status);
    }

    public static boolean isApiOrJsonRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path != null && (path.startsWith("/api/") || path.contains("/api/"))) {
            return true;
        }
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("application/json") && !accept.contains("text/html")) {
            return true;
        }
        return "XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"));
    }

    private static String secondSegment(String path) {
        if (path == null || !path.startsWith("/")) {
            return "";
        }
        int firstEnd = path.indexOf('/', 1);
        if (firstEnd < 0 || firstEnd + 1 >= path.length()) {
            return "";
        }
        int secondEnd = path.indexOf('/', firstEnd + 1);
        return secondEnd < 0 ? path.substring(firstEnd + 1) : path.substring(firstEnd + 1, secondEnd);
    }
}
