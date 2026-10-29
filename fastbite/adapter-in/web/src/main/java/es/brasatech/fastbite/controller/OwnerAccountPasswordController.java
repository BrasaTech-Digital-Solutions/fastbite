package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.office.OwnerPasswordPort;
import es.brasatech.fastbite.application.tenant.PasswordPolicy;
import es.brasatech.fastbite.security.AccountSessionInvalidator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class OwnerAccountPasswordController {
    private final OwnerPasswordPort passwords;
    private final PasswordEncoder encoder;
    private final AccountSessionInvalidator sessions;
    private final es.brasatech.fastbite.application.platform.PlatformLink link;
    public OwnerAccountPasswordController(OwnerPasswordPort passwords, PasswordEncoder encoder, AccountSessionInvalidator sessions, es.brasatech.fastbite.application.platform.PlatformLink link) {
        this.passwords = passwords; this.encoder = encoder; this.sessions = sessions; this.link = link;
    }
    @PostMapping("/owner/account/password")
    public String change(Authentication authentication, HttpServletRequest request,
            @RequestParam String currentPassword, @RequestParam String password, @RequestParam String confirmation) {
        if (link.linkedSandbox()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        String username = authentication.getName();
        var oldHash = passwords.passwordHash(username);
        if (oldHash.isEmpty() || !encoder.matches(currentPassword, oldHash.get())
                || !PasswordPolicy.isValid(password) || !password.equals(confirmation)
                || !passwords.replacePassword(username, oldHash.get(), encoder.encode(password))) {
            return "redirect:/owner/console?view=settings&passwordError";
        }
        sessions.invalidate(username, null, request.getSession().getId());
        return "redirect:/owner/console?view=settings&passwordChanged";
    }
}
