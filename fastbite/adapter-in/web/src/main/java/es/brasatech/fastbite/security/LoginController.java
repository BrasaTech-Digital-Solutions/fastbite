package es.brasatech.fastbite.security;

import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Optional;

@Controller
public class LoginController {

    private final PlatformLink link;
    private final Optional<FiscalProfileService> profiles;

    public LoginController(PlatformLink link, Optional<FiscalProfileService> profiles) {
        this.link = link;
        this.profiles = profiles;
    }

    /** The tenant comes from TenantContextFilter; the view gets it through TenantControllerAdvice. */
    @GetMapping({"/login", "/{tenantId}/login"})
    public String login(Model model) {
        // Owners enter a linked sandbox through production; staff still sign in at their restaurant
        if (link.linkedSandbox() && TenantContext.getCurrentTenant() == null) {
            return "redirect:" + link.url() + "/owner/sandbox";
        }
        String tenant = TenantContext.getCurrentTenant();
        if (tenant != null) {
            String name = profiles.map(service -> service.get().displayName()).filter(value -> !value.isBlank()).orElse(tenant);
            model.addAttribute("restaurantName", name);
        }
        return "fastfood/login";
    }
}
