package es.brasatech.fastbite.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * In a linked sandbox, sends {@link ProductionOnly} actions to production: a page to the same page
 * there, a form to production's owner console. Standalone environments and production take them as
 * before. Read straight from the configuration, so every web slice that imports this class has it.
 */
@Configuration
public class ProductionOnlyConfig implements WebMvcConfigurer {

    private final String productionUrl;

    public ProductionOnlyConfig(Environment configuration) {
        boolean sandbox = !"production".equalsIgnoreCase(configuration.getProperty("fastbite.environment", "sandbox").strip());
        String link = configuration.getProperty("fastbite.link.url", "").strip();
        this.productionUrl = sandbox && !link.isEmpty() ? (link.endsWith("/") ? link.substring(0, link.length() - 1) : link) : null;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (productionUrl == null) {
            return;
        }
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                if (!(handler instanceof HandlerMethod method) || (!method.hasMethodAnnotation(ProductionOnly.class)
                        && !method.getBeanType().isAnnotationPresent(ProductionOnly.class))) {
                    return true;
                }
                String path = request.getRequestURI().substring(request.getContextPath().length());
                String query = request.getQueryString();
                response.sendRedirect("GET".equalsIgnoreCase(request.getMethod())
                        ? productionUrl + path + (query == null ? "" : "?" + query)
                        : productionUrl + "/owner/console");
                return false;
            }
        });
    }
}
