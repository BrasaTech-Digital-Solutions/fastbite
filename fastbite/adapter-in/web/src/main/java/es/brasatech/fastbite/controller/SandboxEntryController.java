package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.office.UserService;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.platform.SandboxEntryService;
import es.brasatech.fastbite.application.platform.SandboxSignInService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.domain.user.UserDto;
import es.brasatech.fastbite.security.TenantUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.Optional;

/**
 * "Enter sandbox" without a second password. Production hands the signed-in owner a single-use link
 * into the sandbox; the sandbox takes it, signs the owner in and opens the location.
 */
@Controller
@Slf4j
public class SandboxEntryController {

    private final SandboxEntryService entry;
    private final SandboxSignInService signIn;
    private final TenantLocationService locations;
    private final UserService users;
    private final PlatformLink link;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public SandboxEntryController(SandboxEntryService entry, SandboxSignInService signIn, TenantLocationService locations,
            UserService users, PlatformLink link) {
        this.entry = entry;
        this.signIn = signIn;
        this.locations = locations;
        this.users = users;
        this.link = link;
    }

    /**
     * Production: into the sandbox, from the owner console or sent back by the sandbox's sign-in page.
     * Without a location, the owner lands on their first one and can switch there.
     */
    @GetMapping("/owner/sandbox")
    public String enter(@RequestParam(required = false) String location, Principal owner, RedirectAttributes flash,
            HttpServletResponse response) {
        if (!entry.available()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String tenant = location;
        if (tenant == null || tenant.isBlank()) {
            var own = locations.getLocationsByOwner(owner.getName()).stream().map(TenantLocation::tenantId).sorted().toList();
            if (own.isEmpty()) {
                return "redirect:/owner/console";
            }
            tenant = own.getFirst();
        }
        SandboxEntryService.Entry result;
        try {
            result = entry.enter(owner.getName(), tenant);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        switch (result.state()) {
            case READY -> {
                protect(response);
                return "redirect:" + result.url();
            }
            case PREPARING -> flash.addFlashAttribute("notice",
                    "Your practice space for " + tenant + " is being prepared. Try again in a minute.");
            case SUBSCRIPTION_ENDED -> flash.addFlashAttribute("error", "The trial or subscription of " + tenant
                    + " has ended. Renew it in Billing to practise in the sandbox again.");
        }
        return "redirect:/owner/console?location=" + tenant;
    }

    /** The sandbox: takes production's link, once, and signs the owner in. */
    @GetMapping(SandboxEntryService.ENTER_PATH)
    public String signIn(@RequestParam(defaultValue = "") String token, HttpServletRequest request,
            HttpServletResponse response, Model model) {
        // Production sends owners to the platform's own address, never to a restaurant's
        if (!signIn.active() || TenantContext.getCurrentTenant() != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        protect(response);
        var outcome = signIn.accept(token);
        var owner = outcome.accepted()
                ? users.findByUsername(outcome.owner()).filter(u -> u.active() && u.isOwner())
                : Optional.<UserDto>empty();
        if (owner.isEmpty()) {
            var refusal = outcome.accepted() ? SandboxSignInService.Refusal.NOT_READY : outcome.refusal();
            response.setStatus(refusal == SandboxSignInService.Refusal.INVALID ? 400 : 503);
            model.addAttribute("refusal", refusal.name());
            model.addAttribute("enterUrl", link.url() + "/owner/sandbox");
            return "fastfood/sandboxEntry";
        }
        signInAs(owner.get(), request, response);
        log.info("{} entered the sandbox through production", owner.get().username());
        return "redirect:/owner/console?location=" + outcome.tenantId();
    }

    /** A fresh session, so whoever used this browser here before is signed out. */
    private void signInAs(UserDto owner, HttpServletRequest request, HttpServletResponse response) {
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        request.getSession(true);
        var user = new TenantUser(owner.username(), "", true,
                owner.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList(), null);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }

    /** The link signs someone in: keep it out of caches and of the next site's Referer. */
    private static void protect(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
