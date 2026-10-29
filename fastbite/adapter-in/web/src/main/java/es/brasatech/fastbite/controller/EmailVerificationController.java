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
    public EmailVerificationController(PendingSignupService signup) { this.signup = signup; }
    @GetMapping("/verify-email")
    public String verify(@RequestParam(defaultValue = "") String token, Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        try {
            if (signup.verify(token)) return "redirect:/login?verified";
        } catch (es.brasatech.fastbite.domain.tenant.TenantProvisioningInProgressException busy) {
            model.addAttribute("token", token);
            return "fastfood/verifyEmailWaiting";
        }
        response.setStatus(400);
        return "fastfood/verifyEmailInvalid";
    }
}
