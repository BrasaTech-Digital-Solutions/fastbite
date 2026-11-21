package es.brasatech.fastbite.config;

import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationGate;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationRequiredException;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Enforces {@link RequiresVerifactuAuthorization} at the request edge before subscription or
 * controller logic runs. There is no bypass of any kind in production.
 */
@Configuration
public class VerifactuAccessConfig implements WebMvcConfigurer {

    private static final String FORBIDDEN_BODY = "{\"error\":\""
            + VerifactuAuthorizationRequiredException.DEFAULT_MESSAGE
            + "\",\"code\":\""
            + VerifactuAuthorizationRequiredException.ERROR_CODE
            + "\"}";

    private final VerifactuAuthorizationGate gate;

    public VerifactuAccessConfig(VerifactuAuthorizationGate gate) {
        this.gate = gate;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws Exception {
                if (handler instanceof HandlerMethod method
                        && method.hasMethodAnnotation(RequiresVerifactuAuthorization.class)) {
                    String tenant = TenantContext.getCurrentTenant();
                    if (!gate.mayIssueTickets(tenant)) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json");
                        response.setCharacterEncoding("UTF-8");
                        response.getWriter().write(FORBIDDEN_BODY);
                        return false;
                    }
                }
                return true;
            }
        }).order(Ordered.HIGHEST_PRECEDENCE);
    }
}
