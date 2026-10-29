package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.tenant.PasswordPolicy;
import es.brasatech.fastbite.application.tenant.PendingSignupPort;
import es.brasatech.fastbite.application.tenant.PendingSignupService;
import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;

@Controller
@Slf4j
public class SignupController {
    private static final String SIGNUP_ID = "pendingSignupId";
    private static final String SIGNUP_EMAIL = "pendingSignupEmail";
    private static final String SIGNUP_REAL = "pendingSignupReal";
    private final PendingSignupService signup;
    private final PasswordEncoder passwordEncoder;
    private final PlatformLink link;
    private final Clock clock;
    private final MessageSource messages;
    private final String termsVersion;

    public SignupController(PendingSignupService signup, PasswordEncoder passwordEncoder, PlatformLink link,
            Clock clock, MessageSource messages, @Value("${fastbite.legal.updated:27/10/2026}") String termsVersion) {
        this.signup = signup; this.passwordEncoder = passwordEncoder; this.link = link;
        this.clock = clock; this.messages = messages; this.termsVersion = termsVersion;
    }
    @GetMapping("/signup")
    public String signup() {
        return link.linkedSandbox() ? "redirect:" + link.url() + "/signup" : "fastfood/signup";
    }
    @PostMapping("/signup")
    public String registerTenant(@RequestParam String tenantId, @RequestParam String username,
            @RequestParam String password, @RequestParam String fullName, @RequestParam String email,
            @RequestParam(defaultValue = "RESTAURANT") String plan,
            @RequestParam(defaultValue = "false") boolean acceptTerms,
            @RequestParam(defaultValue = "false") boolean marketing, HttpServletRequest request, Model model) {
        if (link.linkedSandbox()) return "redirect:" + link.url() + "/signup";
        model.addAttribute("tenantId", tenantId); model.addAttribute("username", username);
        model.addAttribute("fullName", fullName); model.addAttribute("email", email);
        model.addAttribute("plan", plan); model.addAttribute("marketing", marketing);
        try {
            if (!acceptTerms) throw new LocalizedRejection("signup.error.terms", "Accept the terms of service and the data processing agreement to create your restaurant.");
            PasswordPolicy.require(password);
            String normalized = signup.normalizeEmail(email);
            var requestData = new PendingSignupPort.Signup(tenantId, username, fullName, passwordEncoder.encode(password), plan,
                    normalized, LocaleContextHolder.getLocale().getLanguage(), termsVersion, clock.instant(), marketing);
            var result = signup.start(requestData);
            var session = request.getSession(true);
            session.setAttribute(SIGNUP_ID, result.pendingId());
            session.setAttribute(SIGNUP_EMAIL, normalized);
            session.setAttribute(SIGNUP_REAL, result.pending());
            return "redirect:/signup/check-email";
        } catch (IllegalArgumentException invalid) {
            log.warn("Failed tenant registration due to input validation");
            model.addAttribute("error", RejectionMessages.resolve(invalid, messages));
            return "fastfood/signup";
        } catch (Exception error) {
            log.error("Pending registration could not be created ({})", error.getClass().getSimpleName());
            model.addAttribute("error", "We could not finish creating your restaurant. Please try again.");
            return "fastfood/signup";
        }
    }
    @GetMapping("/signup/username-available")
    @ResponseBody
    public boolean usernameAvailable(@RequestParam String username) { return signup.usernameAvailable(username); }

    @GetMapping("/signup/check-email")
    public String checkEmail(HttpServletRequest request, Model model) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(SIGNUP_ID) == null) return "redirect:/signup";
        String id = (String) session.getAttribute(SIGNUP_ID);
        if (Boolean.TRUE.equals(session.getAttribute(SIGNUP_REAL)) && !signup.isPending(id)) model.addAttribute("expired", true);
        model.addAttribute("email", session.getAttribute(SIGNUP_EMAIL));
        return "fastfood/checkEmail";
    }
    @PostMapping("/signup/check-email/resend")
    public String resend(HttpServletRequest request) {
        HttpSession session = signupSession(request);
        if (Boolean.TRUE.equals(session.getAttribute(SIGNUP_REAL))) signup.resend((String) session.getAttribute(SIGNUP_ID));
        return "redirect:/signup/check-email?sent";
    }
    @PostMapping("/signup/check-email/change")
    public String changeEmail(@RequestParam String email, HttpServletRequest request, Model model) {
        HttpSession session = signupSession(request);
        String id = (String) session.getAttribute(SIGNUP_ID);
        try {
            String normalized = signup.normalizeEmail(email);
            if (Boolean.TRUE.equals(session.getAttribute(SIGNUP_REAL))) {
                if (!signup.isPending(id)) return "redirect:/signup/check-email";
                signup.changeEmail(id, normalized);
                session.setAttribute(SIGNUP_REAL, signup.isPending(id));
            }
            session.setAttribute(SIGNUP_EMAIL, normalized);
            return "redirect:/signup/check-email?sent";
        } catch (IllegalArgumentException invalid) {
            model.addAttribute("email", session.getAttribute(SIGNUP_EMAIL));
            model.addAttribute("error", RejectionMessages.resolve(invalid, messages));
            return "fastfood/checkEmail";
        }
    }
    private static HttpSession signupSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(SIGNUP_ID) == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return session;
    }
}
