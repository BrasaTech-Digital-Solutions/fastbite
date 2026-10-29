package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.tenant.OwnerSetupService;
import es.brasatech.fastbite.application.tenant.PasswordPolicy;
import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import org.springframework.context.MessageSource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;


@Controller
public class OwnerPasswordSetupController {
    private final OwnerSetupService setup;
    private final PasswordEncoder encoder;
    private final MessageSource messages;

    public OwnerPasswordSetupController(OwnerSetupService setup, PasswordEncoder encoder, MessageSource messages) {
        this.setup = setup;
        this.encoder = encoder;
        this.messages = messages;
    }

    @GetMapping("/set-password")
    public String form(@RequestParam(defaultValue = "") String token, Model model, HttpServletResponse response) {
        protect(response);
        if (!setup.isValid(token)) return invalid(model, response);
        model.addAttribute("token", token);
        return "fastfood/setPassword";
    }

    @PostMapping("/set-password")
    public String submit(@RequestParam(defaultValue = "") String token,
            @RequestParam(defaultValue = "") String password, @RequestParam(defaultValue = "") String confirmation,
            Model model, HttpServletResponse response) {
        protect(response);
        if (!setup.isValid(token)) return invalid(model, response);
        try {
            PasswordPolicy.require(password);
            if (!password.equals(confirmation))
                throw new LocalizedRejection("password.mismatch", "The passwords do not match.");
        } catch (LocalizedRejection rejection) {
            response.setStatus(400);
            model.addAttribute("token", token);
            model.addAttribute("error", RejectionMessages.resolve(rejection, messages));
            return "fastfood/setPassword";
        }
        if (!setup.complete(token, encoder.encode(password))) return invalid(model, response);
        model.addAttribute("complete", true);
        return "fastfood/setPassword";
    }

    private static String invalid(Model model, HttpServletResponse response) {
        response.setStatus(400);
        model.addAttribute("invalid", true);
        return "fastfood/setPassword";
    }

    private static void protect(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
