package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.office.PasswordRecoveryService;
import es.brasatech.fastbite.application.tenant.PasswordPolicy;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.security.AccountSessionInvalidator;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PasswordRecoveryController {
    private final PasswordRecoveryService recovery;
    private final PasswordEncoder encoder;
    private final AccountSessionInvalidator sessions;
    public PasswordRecoveryController(PasswordRecoveryService recovery, PasswordEncoder encoder, AccountSessionInvalidator sessions) {
        this.recovery = recovery; this.encoder = encoder; this.sessions = sessions;
    }
    @GetMapping("/forgot-password") public String forgot(Model model) {
        model.addAttribute("tenantId", TenantContext.getCurrentTenant()); return "fastfood/forgotPassword";
    }
    @PostMapping("/forgot-password") public String request(@RequestParam(defaultValue = "") String identifier, Model model) {
        recovery.request(identifier, TenantContext.getCurrentTenant());
        model.addAttribute("sent", true); model.addAttribute("tenantId", TenantContext.getCurrentTenant());
        return "fastfood/forgotPassword";
    }
    @GetMapping("/reset-password") public String resetForm(@RequestParam(defaultValue = "") String token, Model model, HttpServletResponse response) {
        protect(response); if (!recovery.valid(token)) return invalid(model, response);
        model.addAttribute("token", token); return "fastfood/resetPassword";
    }
    @PostMapping("/reset-password") public String reset(@RequestParam(defaultValue = "") String token,
            @RequestParam(defaultValue = "") String password, @RequestParam(defaultValue = "") String confirmation,
            Model model, HttpServletResponse response) {
        protect(response);
        if (!recovery.valid(token)) return invalid(model, response);
        if (!PasswordPolicy.isValid(password) || !password.equals(confirmation)) {
            response.setStatus(400); model.addAttribute("token", token); model.addAttribute("invalidPassword", true); return "fastfood/resetPassword";
        }
        var account = recovery.reset(token, encoder.encode(password));
        if (account.isEmpty()) return invalid(model, response);
        sessions.invalidate(account.get().username(), account.get().tenantId(), null);
        return account.get().tenantId() == null ? "redirect:/login?reset" : "redirect:/login?reset&tenantId=" + account.get().tenantId();
    }
    private static String invalid(Model model, HttpServletResponse response) {
        response.setStatus(400); model.addAttribute("invalid", true); return "fastfood/resetPassword";
    }
    private static void protect(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store"); response.setHeader("Referrer-Policy", "no-referrer");
    }
}
