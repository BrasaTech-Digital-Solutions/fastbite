package es.brasatech.fastbite.security;

import es.brasatech.fastbite.application.office.OwnerEmailService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Locale;

/** An authenticated owner must verify an address before using the application. */
public class OwnerVerificationFilter extends OncePerRequestFilter {
    public static final String VERIFIED_SESSION_ATTRIBUTE = "ownerEmailVerified";
    private final OwnerEmailService owners;
    private final boolean production;
    private final MessageSource messages;
    public OwnerVerificationFilter(OwnerEmailService owners, PlatformEnvironment environment, MessageSource messages) {
        this.owners = owners; this.production = environment.production(); this.messages = messages;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (production && auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof TenantUser user
                && user.isPlatformAccount() && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_OWNER"))
                && !allowed(path) && !verified(request, user.getUsername())) {
            if (jsonRequest(request, path)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setCharacterEncoding("UTF-8");
                response.setContentType("application/json");
                String message = messages.getMessage("account.email.required", null, "Verify your email to use your account.", org.springframework.context.i18n.LocaleContextHolder.getLocale());
                response.getWriter().write("{\"error\":" + jsonString(message) + "}");
            } else response.sendRedirect(request.getContextPath() + "/owner/account/verify");
            return;
        }
        chain.doFilter(request, response);
    }
    private boolean verified(HttpServletRequest request, String username) {
        var session = request.getSession(false);
        if (session != null && username.equals(session.getAttribute(VERIFIED_SESSION_ATTRIBUTE))) return true;
        boolean verified = owners.status(username).map(s -> s.verified()).orElse(false);
        if (verified) request.getSession(true).setAttribute(VERIFIED_SESSION_ATTRIBUTE, username);
        return verified;
    }
    private static boolean allowed(String path) {
        return path.equals("/owner/account/verify") || path.equals("/owner/account/verify/resend")
                || path.equals("/verify-email") || path.equals("/logout") || path.endsWith("/logout")
                || path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/")
                || path.startsWith("/webjars/") || path.equals("/error") || path.equals("/favicon.ico")
                || path.equals("/legal") || path.startsWith("/legal/")
                || path.equals("/verifactu/declaracion-responsable")
                || path.startsWith("/verifactu/declaracion-responsable/");
    }
    private static boolean jsonRequest(HttpServletRequest request, String path) {
        String accept = request.getHeader("Accept");
        return path.startsWith("/api/") || path.contains("/api/") || path.endsWith(".json")
                || (path.startsWith("/owner/workspace/") && (path.endsWith("/backup-preview")
                    || path.endsWith("/promote/preview") || path.endsWith("/promote/confirm") || path.endsWith("/promote/undo")))
                || (accept != null && accept.toLowerCase(Locale.ROOT).contains("application/json"))
                || request.getHeader("X-Requested-With") != null;
    }
    private static String jsonString(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\"' || c == '\\') result.append('\\').append(c);
            else if (c < 0x20) result.append(String.format("\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('\"').toString();
    }
}
