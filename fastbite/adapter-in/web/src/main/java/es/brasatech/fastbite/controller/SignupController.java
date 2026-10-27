package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.tenant.SignupConsents;
import es.brasatech.fastbite.application.tenant.TenantSignupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@Slf4j
public class SignupController {

    private final TenantSignupService tenantSignupService;
    private final PasswordEncoder passwordEncoder;
    private final PlatformLink link;
    private final java.time.Clock clock;
    /** The version of the legal texts an owner accepts: the date they show (see LegalController). */
    private final String termsVersion;

    public SignupController(TenantSignupService tenantSignupService, PasswordEncoder passwordEncoder, PlatformLink link,
            java.time.Clock clock, @Value("${fastbite.legal.updated:27/10/2026}") String termsVersion) {
        this.tenantSignupService = tenantSignupService;
        this.passwordEncoder = passwordEncoder;
        this.link = link;
        this.clock = clock;
        this.termsVersion = termsVersion;
    }

    @GetMapping("/signup")
    public String signup() {
        // Owners sign up in production, which sets their locations up in its sandbox
        if (link.linkedSandbox()) {
            return "redirect:" + link.url() + "/signup";
        }
        return "fastfood/signup";
    }

    @PostMapping("/signup")
    public String registerTenant(
            @RequestParam String tenantId,
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam String fullName,
            @RequestParam(defaultValue = "RESTAURANT") String plan,
            @RequestParam(defaultValue = "false") boolean acceptTerms,
            @RequestParam(defaultValue = "false") boolean marketing,
            jakarta.servlet.http.HttpServletRequest request,
            Model model) {
        if (link.linkedSandbox()) {
            return "redirect:" + link.url() + "/signup";
        }
        model.addAttribute("tenantId", tenantId);
        model.addAttribute("username", username);
        model.addAttribute("fullName", fullName);
        model.addAttribute("plan", plan);
        model.addAttribute("marketing", marketing);
        try {
            if (!acceptTerms)
                throw new IllegalArgumentException("Accept the terms of service and the data processing agreement to create your restaurant.");
            if (password.length() < 10 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
                throw new IllegalArgumentException("Use a password of at least 10 characters and at most 72 UTF-8 bytes.");
            String encodedPassword = passwordEncoder.encode(password);
            tenantSignupService.registerTenant(tenantId, username, encodedPassword, fullName, plan,
                    new SignupConsents(termsVersion, marketing, clock.instant()));
            
            String normalizedTenant = es.brasatech.fastbite.application.tenant.TenantRegistrationRules.tenantId(tenantId);
            String subdomainUrl = "/" + normalizedTenant + "/login";

            model.addAttribute("registeredTenantId", tenantId);
            model.addAttribute("subdomainUrl", subdomainUrl);
            String welcomeMsg = "FREE".equalsIgnoreCase(plan)
                    ? "Restaurant " + tenantId + " has been successfully registered on the Free Plan (0€/mo). You can now log in."
                    : "Restaurant " + tenantId + " has been successfully registered. Your 30-day trial is ready. You can now log in.";
            model.addAttribute("success", welcomeMsg);
            return "fastfood/signup";
        } catch (IllegalArgumentException e) {
            log.warn("Failed tenant registration due to input validation: {}", e.getMessage());
            model.addAttribute("error", e.getMessage());
            return "fastfood/signup";
        } catch (Exception e) {
            log.error("Failed tenant registration and self-provisioning: ", e);
            model.addAttribute("error", "We could not finish creating your restaurant. Please try again.");
            return "fastfood/signup";
        }
    }
}
