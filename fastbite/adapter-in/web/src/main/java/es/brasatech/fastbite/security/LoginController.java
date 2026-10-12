package es.brasatech.fastbite.security;

import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    private final PlatformEnvironment environment;
    private final PlatformLink link;

    public LoginController(PlatformEnvironment environment, PlatformLink link) {
        this.environment = environment;
        this.link = link;
    }

    /** The tenant comes from TenantContextFilter; the view gets it through TenantControllerAdvice. */
    @GetMapping({"/login", "/{tenantId}/login"})
    public String login() {
        // Owners enter a linked sandbox through production; staff still sign in at their restaurant
        if (environment.sandbox() && link.linked() && TenantContext.getCurrentTenant() == null) {
            return "redirect:" + link.url() + "/owner/sandbox";
        }
        return "fastfood/login";
    }
}
