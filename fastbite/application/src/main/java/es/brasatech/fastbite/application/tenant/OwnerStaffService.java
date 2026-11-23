package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.application.office.UserService;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.exception.ResourceNotFoundException;
import es.brasatech.fastbite.domain.user.Role;
import es.brasatech.fastbite.domain.user.UserDto;

import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

@org.springframework.stereotype.Service
public class OwnerStaffService {

    private static final Logger LOGGER = Logger.getLogger(OwnerStaffService.class.getName());

    private final UserService userService;
    private final TenantLocationService tenantLocationService;

    public OwnerStaffService(UserService userService, TenantLocationService tenantLocationService) {
        this.userService = userService;
        this.tenantLocationService = tenantLocationService;
    }

    public void checkOwnership(String ownerUsername, String tenantId) {
        if (ownerUsername == null || !tenantLocationService.isOwnerOf(ownerUsername, tenantId)) {
            throw new ResourceNotFoundException();
        }
    }

    public List<UserDto> listStaff(String ownerUsername, String tenantId) {
        checkOwnership(ownerUsername, tenantId);
        String previousTenant = TenantContext.getCurrentTenant();
        try {
            TenantContext.setCurrentTenant(tenantId);
            return userService.findAll();
        } finally {
            if (previousTenant == null) TenantContext.clear(); else TenantContext.setCurrentTenant(previousTenant);
        }
    }

    public List<UserDto> createStaff(String ownerUsername, String tenantId, String username, String fullName, String encodedPassword, Role role) {
        checkOwnership(ownerUsername, tenantId);
        if (username == null || !username.matches("[a-zA-Z0-9_.-]{1,100}")) {
            throw new IllegalArgumentException("Choose a username without @.");
        }
        if (role == Role.OWNER) {
            throw new IllegalArgumentException("Owners are platform accounts and can't be added as staff.");
        }
        String previousTenant = TenantContext.getCurrentTenant();
        try {
            TenantContext.setCurrentTenant(tenantId);
            // Saving upserts by username, so an existing name would overwrite that account.
            if (userService.findByUsername(username).isPresent()) {
                throw new IllegalArgumentException("Username '" + username + "' is already taken.");
            }

            UserDto newUser = new UserDto(
                    null,
                    username,
                    encodedPassword,
                    fullName,
                    Set.of(role),
                    true,
                    tenantId
            );
            userService.save(newUser);
            return userService.findAll();
        } finally {
            if (previousTenant == null) TenantContext.clear(); else TenantContext.setCurrentTenant(previousTenant);
        }
    }

    public List<UserDto> deleteStaff(String ownerUsername, String tenantId, String userId) {
        checkOwnership(ownerUsername, tenantId);
        String previousTenant = TenantContext.getCurrentTenant();
        try {
            TenantContext.setCurrentTenant(tenantId);
            userService.delete(userId);
            return userService.findAll();
        } finally {
            if (previousTenant == null) TenantContext.clear(); else TenantContext.setCurrentTenant(previousTenant);
        }
    }
}
