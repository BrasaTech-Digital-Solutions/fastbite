package es.brasatech.fastbite.security;

import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantContextFilter;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Scopes the signed-in user to the restaurant the request is for:
 * <ul>
 *   <li>Staff accounts keep their roles only inside their own restaurant.</li>
 *   <li>Platform (owner) accounts get ADMIN inside the restaurants they own and only OWNER elsewhere.</li>
 *   <li>Anyone else is treated as anonymous for this request.</li>
 * </ul>
 * Only the current request is affected; the authentication stored in the session is never modified.
 */
public class TenantAccessFilter extends OncePerRequestFilter {

    private static final String ROLE_OWNER = "ROLE_OWNER";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final TenantLocationService tenantLocationService;
    private final TenantRoutingResolver routingResolver;

    public TenantAccessFilter(TenantLocationService tenantLocationService) {
        this(tenantLocationService, null);
    }

    public TenantAccessFilter(TenantLocationService tenantLocationService, TenantRoutingResolver routingResolver) {
        this.tenantLocationService = tenantLocationService;
        this.routingResolver = routingResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            boolean exemptPath = path.equals("/error") || path.equals("/login") || path.equals("/logout")
                    || path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/");
            if (!exemptPath && authentication.getPrincipal() instanceof TenantUser user && !user.isPlatformAccount()) {
                String homeTenant = user.getHomeTenantId();
                boolean active = homeTenant != null && (routingResolver != null
                        ? routingResolver.exists(homeTenant)
                        : tenantLocationService.getLocation(homeTenant).filter(loc -> !loc.isClosed()).isPresent()
                          && !tenantLocationService.isClosed(homeTenant));
                if (!active) {
                    HttpSession session = request.getSession(false);
                    if (session != null) {
                        session.invalidate();
                    }
                    SecurityContextHolder.clearContext();
                    if (TenantContextFilter.isApiOrJsonRequest(request)) {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"error\":\"session_invalidated\"}");
                    } else {
                        response.sendRedirect(request.getContextPath() + "/login");
                    }
                    return;
                }
            }
            Authentication scoped = scope(authentication, TenantContext.getCurrentTenant());
            if (scoped != authentication) {
                // A fresh context, so the one stored in the session stays untouched.
                // A null authentication is filled in as anonymous by AnonymousAuthenticationFilter.
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(scoped);
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }

    Authentication scope(Authentication authentication, String tenantId) {
        if (!(authentication.getPrincipal() instanceof TenantUser user)) {
            return null; // signed in before logins were tenant-scoped
        }
        if (tenantId == null) {
            return user.isPlatformAccount() ? withAuthorities(authentication, this::isNotStaffRole, null) : null;
        }
        if (user.belongsTo(tenantId)) {
            return withAuthorities(authentication, a -> !ROLE_OWNER.equals(a.getAuthority()), null);
        }
        if (user.isPlatformAccount() && tenantLocationService.isOwnerOf(user.getUsername(), tenantId)) {
            return withAuthorities(authentication, a -> true, ROLE_ADMIN);
        }
        return null;
    }

    private boolean isNotStaffRole(GrantedAuthority authority) {
        String name = authority.getAuthority();
        return name == null || !name.startsWith("ROLE_") || ROLE_OWNER.equals(name);
    }

    private static Authentication withAuthorities(Authentication authentication, Predicate<GrantedAuthority> keep,
                                                  String extraRole) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authentication.getAuthorities().stream().filter(keep).forEach(authorities::add);
        if (extraRole != null && authorities.stream().noneMatch(a -> extraRole.equals(a.getAuthority()))) {
            authorities.add(new SimpleGrantedAuthority(extraRole));
        }
        if (authorities.size() == authentication.getAuthorities().size()
                && authorities.containsAll(authentication.getAuthorities())) {
            return authentication;
        }
        UsernamePasswordAuthenticationToken scoped =
                UsernamePasswordAuthenticationToken.authenticated(authentication.getPrincipal(), null, authorities);
        scoped.setDetails(authentication.getDetails());
        return scoped;
    }
}
