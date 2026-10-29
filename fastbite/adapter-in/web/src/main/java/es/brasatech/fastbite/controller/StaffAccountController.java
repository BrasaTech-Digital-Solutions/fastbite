package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.office.StaffAccountService;
import es.brasatech.fastbite.application.tenant.PasswordPolicy;
import es.brasatech.fastbite.security.AccountSessionInvalidator;
import es.brasatech.fastbite.security.TenantUser;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class StaffAccountController {
    private final StaffAccountService staff;
    private final PasswordEncoder encoder;
    private final AccountSessionInvalidator sessions;
    public StaffAccountController(StaffAccountService staff, PasswordEncoder encoder, AccountSessionInvalidator sessions) {
        this.staff = staff; this.encoder = encoder; this.sessions = sessions;
    }
    private boolean owner(Authentication auth, String tenant) {
        if (auth == null || !(auth.getPrincipal() instanceof TenantUser user)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (user.isPlatformAccount() && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_OWNER"))) return true;
        if (user.belongsTo(tenant) && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_MANAGER"))) return false;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    @GetMapping("/{tenant}/backoffice/team")
    public String team(@PathVariable String tenant, Authentication auth, Model model) {
        boolean isOwner = owner(auth, tenant);
        try { model.addAttribute("staff", staff.list(auth.getName(), isOwner, tenant)); }
        catch (SecurityException denied) { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
        model.addAttribute("tenant", tenant); return "fastfood/staffTeam";
    }
    @GetMapping({"/owner/locations/{tenant}/users/{id}/account", "/{tenant}/backoffice/team/{id}"})
    public String account(@PathVariable String tenant, @PathVariable String id, Authentication auth, jakarta.servlet.http.HttpServletRequest request, Model model) {
        boolean isOwner = owner(auth, tenant);
        try { model.addAttribute("staff", staff.find(auth.getName(), isOwner, tenant, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))); }
        catch (SecurityException denied) { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
        model.addAttribute("base", request.getRequestURI().substring(request.getContextPath().length()));
        return "fastfood/staffAccount";
    }
    @PostMapping({"/owner/locations/{tenant}/users/{id}/account/password", "/{tenant}/backoffice/team/{id}/password"})
    public String password(@PathVariable String tenant, @PathVariable String id, @RequestParam String password, Authentication auth, jakarta.servlet.http.HttpServletRequest request) {
        boolean isOwner = owner(auth, tenant); PasswordPolicy.require(password);
        try { var target = staff.password(auth.getName(), isOwner, tenant, id, encoder.encode(password)); sessions.invalidate(target.username(), tenant, null); }
        catch (SecurityException denied) { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
        return back(request, "/password");
    }
    @PostMapping({"/owner/locations/{tenant}/users/{id}/account/email", "/{tenant}/backoffice/team/{id}/email"})
    public String email(@PathVariable String tenant, @PathVariable String id, @RequestParam String email, Authentication auth, jakarta.servlet.http.HttpServletRequest request) {
        boolean isOwner = owner(auth, tenant);
        try { staff.email(auth.getName(), isOwner, tenant, id, email, LocaleContextHolder.getLocale().getLanguage()); }
        catch (SecurityException denied) { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
        return back(request, "/email");
    }
    @PostMapping({"/owner/locations/{tenant}/users/{id}/account/reset", "/{tenant}/backoffice/team/{id}/reset"})
    public String reset(@PathVariable String tenant, @PathVariable String id, Authentication auth, jakarta.servlet.http.HttpServletRequest request) {
        boolean isOwner = owner(auth, tenant);
        try { staff.reset(auth.getName(), isOwner, tenant, id); }
        catch (SecurityException denied) { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
        return back(request, "/reset");
    }
    private static String back(jakarta.servlet.http.HttpServletRequest request, String suffix) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "redirect:" + path.substring(0, path.length() - suffix.length()) + "?updated";
    }
}
