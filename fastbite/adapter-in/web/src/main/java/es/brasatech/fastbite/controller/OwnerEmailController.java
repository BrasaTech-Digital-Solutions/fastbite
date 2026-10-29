package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.office.OwnerEmailService;
import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class OwnerEmailController {
    private final OwnerEmailService owners;
    private final es.brasatech.fastbite.application.platform.PlatformLink link;
    public OwnerEmailController(OwnerEmailService owners, es.brasatech.fastbite.application.platform.PlatformLink link) { this.owners = owners; this.link = link; }
    private void requireProduction() {
        if (link.linkedSandbox()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    }
    @GetMapping("/owner/account/verify")
    public String form(Authentication auth, Model model) {
        requireProduction();
        owners.status(auth.getName()).ifPresent(s -> { model.addAttribute("pendingEmail", s.pendingEmail()); model.addAttribute("currentEmail", s.email()); });
        return "fastfood/owner/verifyAccount";
    }
    @PostMapping("/owner/account/verify")
    public String request(Authentication auth, @RequestParam String email, jakarta.servlet.http.HttpServletRequest request) {
        requireProduction();
        try {
            owners.request(auth.getName(), email, LocaleContextHolder.getLocale().getLanguage());
            var session = request.getSession(false);
            if (session != null) session.removeAttribute(es.brasatech.fastbite.security.OwnerVerificationFilter.VERIFIED_SESSION_ATTRIBUTE);
        }
        catch (LocalizedRejection invalid) { return "redirect:/owner/account/verify?error"; }
        return "redirect:/owner/account/verify?sent";
    }
    @PostMapping("/owner/account/verify/resend")
    public String resend(Authentication auth) { requireProduction(); owners.resend(auth.getName()); return "redirect:/owner/account/verify?sent"; }
}
