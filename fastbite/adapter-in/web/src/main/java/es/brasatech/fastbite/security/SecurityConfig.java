package es.brasatech.fastbite.security;

import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, TenantLocationService tenantLocationService) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // Public areas
                        .requestMatchers("/", "/menu/**", "/api/calculate-cart", "/api/calculate-confirmation", "/api/toast",
                                "/api/create-order", "/api/order-status", "/api/order-status/live", "/order-confirmation/**", "/select-payment", "/signup", "/api/webhooks/stripe", "/api/stripe/**").permitAll()
                        .requestMatchers("/*/menu/**", "/*/api/calculate-cart", "/*/api/calculate-confirmation", "/*/api/toast",
                                "/*/api/create-order", "/*/api/order-status", "/*/api/order-status/live", "/*/order-confirmation/**", "/*/select-payment", "/*/api/stripe/**").permitAll()
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**", "/user-images/**").permitAll()
                        .requestMatchers("/login", "/error", "/*/login", "/set-password").permitAll()

                        // Owner Console access (strictly Tenant Owners)
                        .requestMatchers("/owner/**").hasRole("OWNER")

                        // Dashboard access (all staff roles + Owner)
                        .requestMatchers("/dashboard", "/dashboard/**", "/counter/**", "/api/order", "/api/order/**", "/api/orders/**", "/api/counter/**",
                                "/*/dashboard", "/*/dashboard/**", "/*/counter/**", "/*/api/order", "/*/api/order/**", "/*/api/orders/**", "/*/api/counter/**")
                        .hasAnyRole("ADMIN", "MANAGER", "CASHIER", "COOK", "WAITER", "OWNER")

                        // Maintenance access (strictly Admin or Owner)
                        .requestMatchers("/*/api/backoffice/maintenance/**").hasAnyRole("ADMIN", "OWNER")

                        // BackOffice access (admin and manager only)
                        .requestMatchers("/backoffice/**", "/api/backoffice/**", "/api/backoffice/orders/reassign-table",
                                "/*/backoffice/**", "/*/api/backoffice/**").hasAnyRole("ADMIN", "MANAGER")

                        // Everything else requires authentication
                        .anyRequest().authenticated())
                // Roles only count inside the restaurant they belong to; see TenantAccessFilter.
                .addFilterBefore(new TenantAccessFilter(tenantLocationService), AnonymousAuthenticationFilter.class)
                .formLogin(form -> form
                        .loginPage("/login")
                        .successHandler(new TenantAuthenticationSuccessHandler())
                        .failureHandler(new TenantAuthenticationFailureHandler())
                        .permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new TenantAuthenticationEntryPoint()))
                .logout(logout -> logout
                        .logoutRequestMatcher(SecurityConfig::isLogoutRequest)
                        .logoutSuccessHandler(new TenantLogoutSuccessHandler())
                        .deleteCookies("JSESSIONID")
                        .invalidateHttpSession(true)
                        .permitAll())
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers("/api/webhooks/stripe")
                )
                .headers(headers -> headers
                        .frameOptions(frameOptions -> frameOptions.sameOrigin())
                );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Prefix for links inside the request's tenant: "" on a tenant host, "/{tenantId}" for path-based
     * tenants, or null outside any tenant. Set by {@link es.brasatech.fastbite.config.TenantContextFilter}.
     */
    private static String tenantUrlPrefix(HttpServletRequest request) {
        return (String) request.getAttribute(TenantRoutingResolver.TENANT_URL_PREFIX);
    }

    /** POST to /logout or /{tenantId}/logout. */
    private static boolean isLogoutRequest(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String first = TenantRoutingResolver.firstSegment(path);
        return path.equals("/logout") || (TenantRoutingResolver.isTenantSegment(first) && path.equals("/" + first + "/logout"));
    }

    private static class TenantAuthenticationEntryPoint implements AuthenticationEntryPoint {
        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {
            String prefix = tenantUrlPrefix(request);
            response.sendRedirect(request.getContextPath() + (prefix != null ? prefix : "") + "/login");
        }
    }

    private static class TenantAuthenticationSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {
        @Override
        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
            String prefix = tenantUrlPrefix(request);
            boolean owner = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));

            if ("".equals(prefix)) {
                setDefaultTargetUrl("/menu");
            } else if (owner) {
                setDefaultTargetUrl("/owner/console");
            } else if (prefix != null) {
                setDefaultTargetUrl(prefix + "/menu");
            } else {
                setDefaultTargetUrl("/");
            }

            // Keep devtools or background JSON requests from becoming the post-login redirect target
            RequestCache requestCache = new HttpSessionRequestCache();
            SavedRequest savedRequest = requestCache.getRequest(request, response);
            if (savedRequest != null) {
                String redirectUrl = savedRequest.getRedirectUrl();
                if (redirectUrl.contains("com.chrome.devtools") || redirectUrl.contains("/appspecific/") || redirectUrl.endsWith(".json") || redirectUrl.contains("/favicon.ico")) {
                    requestCache.removeRequest(request, response);
                }
            }

            super.onAuthenticationSuccess(request, response, authentication);
        }
    }

    private static class TenantLogoutSuccessHandler implements LogoutSuccessHandler {
        @Override
        public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException {
            String prefix = tenantUrlPrefix(request);
            response.sendRedirect(request.getContextPath() + (prefix != null ? prefix + "/menu" : "/signup"));
        }
    }

    private static class TenantAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {
        @Override
        public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException, ServletException {
            String prefix = tenantUrlPrefix(request);
            if (prefix != null) {
                saveException(request, exception);
                getRedirectStrategy().sendRedirect(request, response, prefix + "/login?error");
            } else {
                setDefaultFailureUrl("/login?error");
                super.onAuthenticationFailure(request, response, exception);
            }
        }
    }

    @Bean
    public CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("JSESSIONID");
        // Allows wildcard session cookie sharing (e.g. *.localhost or *.yourdomain.com)
        serializer.setDomainNamePattern("^(?:.+?\\.)?(\\w+\\.\\w+)$");
        return serializer;
    }
}
