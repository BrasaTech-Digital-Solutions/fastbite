package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.tenant.OwnerStaffService;
import es.brasatech.fastbite.domain.user.Role;
import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import es.brasatech.fastbite.domain.user.UserDto;
import es.brasatech.fastbite.application.tenant.PasswordPolicy;
import es.brasatech.fastbite.security.AccountSessionInvalidator;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Arrays;
import java.util.List;

@Controller
@RequestMapping("/owner/locations/{tenantId}/users")
@RequiredArgsConstructor
@Slf4j
public class OwnerStaffController {

    private static final List<Role> STAFF_ROLES = Arrays.stream(Role.values()).filter(role -> role != Role.OWNER).toList();

    private final OwnerStaffService ownerStaffService;
    private final PasswordEncoder passwordEncoder;
    private final MessageSource messages;
    private final AccountSessionInvalidator sessions;

    @GetMapping
    public String listUsers(
            @PathVariable String tenantId,
            Principal principal,
            Model model) {
        
        log.info("Request to listUsers for tenantId: {}. Principal: {}", tenantId, principal);
        if (principal instanceof org.springframework.security.core.Authentication auth) {
            log.info("User authorities: {}", auth.getAuthorities());
        }
        
        String ownerUsername = principal != null ? principal.getName() : null;
        List<UserDto> users = ownerStaffService.listStaff(ownerUsername, tenantId);
        model.addAttribute("users", users);
        model.addAttribute("tenantId", tenantId);
        model.addAttribute("roles", STAFF_ROLES);
        return "fastfood/owner/fragments :: staff-list";
    }

    @PostMapping
    public String createUser(
            @PathVariable String tenantId,
            @RequestParam String username,
            @RequestParam String fullName,
            @RequestParam String password,
            @RequestParam Role role,
            Principal principal,
            Model model) {

        String ownerUsername = principal != null ? principal.getName() : null;
        try {
            PasswordPolicy.require(password);
            String encodedPassword = passwordEncoder.encode(password);
            List<UserDto> users = ownerStaffService.createStaff(ownerUsername, tenantId, username, fullName, encodedPassword, role);
            model.addAttribute("users", users);
            model.addAttribute("tenantId", tenantId);
            model.addAttribute("roles", STAFF_ROLES);
            model.addAttribute("success", messages.getMessage("workspace.flash.staff.created", new Object[]{username}, LocaleContextHolder.getLocale()));
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                log.warn("Staff account creation rejected for tenant {}", tenantId);
            } else {
                log.error("Failed to create staff user for tenant {}", tenantId, e);
            }
            model.addAttribute("error", e instanceof LocalizedRejection ? RejectionMessages.resolve(e, messages)
                    : messages.getMessage("workspace.flash.failed", null, "The action could not be completed. Check the details and try again.", LocaleContextHolder.getLocale()));
            
            List<UserDto> users = ownerStaffService.listStaff(ownerUsername, tenantId);
            model.addAttribute("users", users);
            model.addAttribute("tenantId", tenantId);
            model.addAttribute("roles", STAFF_ROLES);
        }
        return "fastfood/owner/fragments :: staff-list";
    }

    @PostMapping("/delete")
    public String deleteUser(
            @PathVariable String tenantId,
            @RequestParam String userId,
            Principal principal,
            Model model) {

        String ownerUsername = principal != null ? principal.getName() : null;
        String deletedUsername = ownerStaffService.listStaff(ownerUsername, tenantId).stream()
                .filter(u -> userId.equals(u.id()))
                .map(UserDto::username)
                .findFirst()
                .orElse(null);
        List<UserDto> users = ownerStaffService.deleteStaff(ownerUsername, tenantId, userId);
        if (deletedUsername != null && users.stream().noneMatch(u -> userId.equals(u.id()))) {
            sessions.invalidate(deletedUsername, tenantId, null);
        }
        model.addAttribute("users", users);
        model.addAttribute("tenantId", tenantId);
        model.addAttribute("roles", STAFF_ROLES);
        model.addAttribute("success", messages.getMessage("workspace.flash.staff.deleted", null, LocaleContextHolder.getLocale()));
        return "fastfood/owner/fragments :: staff-list";
    }
}
