package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.tenant.PendingSignupService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class EmailVerificationController {
    private final PendingSignupService signup;
    private final es.brasatech.fastbite.application.office.OwnerEmailService owners;
    private final es.brasatech.fastbite.application.office.StaffAccountService staff;
    public EmailVerificationController(PendingSignupService signup, es.brasatech.fastbite.application.office.OwnerEmailService owners, es.brasatech.fastbite.application.office.StaffAccountService staff) { this.signup = signup; this.owners = owners; this.staff = staff; }
    @GetMapping("/verify-email")
    public String verify(@RequestParam(defaultValue = "") String token, Model model, HttpServletResponse response, org.springframework.security.core.Authentication auth) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        try {
            if (owners.verify(token)) return auth != null && auth.isAuthenticated() && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) ? "redirect:/owner/console" : "redirect:/login?verified";
            if (staff.verify(token)) {
                if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof es.brasatech.fastbite.security.TenantUser user)
                    return user.isPlatformAccount() ? "redirect:/owner/console" : "redirect:/" + user.getHomeTenantId() + "/dashboard";
                return "redirect:/login?verified";
            }
            if (signup.verify(token)) return "redirect:/login?verified";
        } catch (es.brasatech.fastbite.domain.tenant.TenantProvisioningInProgressException busy) {
            model.addAttribute("token", token);
            return "fastfood/verifyEmailWaiting";
        }
        response.setStatus(400);
        return "fastfood/verifyEmailInvalid";
    }
}
