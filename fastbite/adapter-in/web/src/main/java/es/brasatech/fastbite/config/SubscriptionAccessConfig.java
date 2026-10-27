package es.brasatech.fastbite.config;

import es.brasatech.fastbite.application.tenant.SubscriptionService;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Existing orders, sign-in, billing and exports remain usable after a trial/subscription ends. */
@Configuration
public class SubscriptionAccessConfig implements WebMvcConfigurer {
    private final SubscriptionService subscriptions;
    private final boolean localDemo;
    public SubscriptionAccessConfig(SubscriptionService subscriptions, org.springframework.core.env.Environment env) { this.subscriptions=subscriptions; this.localDemo=env.acceptsProfiles(org.springframework.core.env.Profiles.of("local", "demo")); }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                String tenant = TenantContext.getCurrentTenant();
                if (handler instanceof HandlerMethod method && method.hasMethodAnnotation(RequiresOrderingSubscription.class)
                        && !(localDemo && "kebab".equals(tenant))
                        && !subscriptions.canTakeOrders(tenant)) {
                    response.setStatus(402);
                    response.setContentType("application/json");
                    String msg = subscriptions.isFreePlan(tenant)
                            ? "{\"error\":\"New orders are paused on the Free Plan until diner payments are connected in Location settings.\"}"
                            : "{\"error\":\"New orders are paused. The restaurant owner can renew in Billing.\"}";
                    response.getWriter().write(msg);
                    return false;
                }
                return true;
            }
        });
    }
}
