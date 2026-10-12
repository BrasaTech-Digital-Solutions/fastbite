package es.brasatech.fastbite.security;

import es.brasatech.fastbite.application.office.UserService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.user.UserDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Signs users in against the right schema:
 * outside a restaurant, or as the owner of the current restaurant, against the platform schema;
 * otherwise against the current restaurant's staff accounts.
 *
 * <p>A linked sandbox takes no owner's password: owners enter it through production's sign-in link.
 */
@Service
@Slf4j
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserService userService;
    private final TenantLocationService tenantLocationService;
    private final boolean ownersEnterThroughProduction;

    public UserDetailsServiceImpl(UserService userService, TenantLocationService tenantLocationService,
            PlatformEnvironment environment, PlatformLink link) {
        this.userService = userService;
        this.tenantLocationService = tenantLocationService;
        this.ownersEnterThroughProduction = environment.sandbox() && link.linked();
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String tenantId = TenantContext.getCurrentTenant();
        Optional<UserDto> platformUser = inPlatformSchema(() -> userService.findByUsername(username));

        if (tenantId == null || platformUser.filter(u -> tenantLocationService.isOwnerOf(u.username(), tenantId)).isPresent()) {
            log.debug("Signing in platform account {}", username);
            if (ownersEnterThroughProduction && platformUser.filter(UserDto::isOwner).isPresent()) {
                log.info("Refused password sign-in of owner {}: this sandbox is entered through production", username);
                throw new UsernameNotFoundException("Owners enter the sandbox through production");
            }
            return platformUser.map(u -> toUserDetails(u, null))
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        }

        log.debug("Signing in staff account {} of tenant {}", username, tenantId);
        return userService.findByUsername(username)
                .map(u -> toUserDetails(u, tenantId))
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    private <T> T inPlatformSchema(Supplier<T> query) {
        String tenantId = TenantContext.getCurrentTenant();
        TenantContext.clear();
        try {
            return query.get();
        } finally {
            if (tenantId != null) {
                TenantContext.setCurrentTenant(tenantId);
            }
        }
    }

    private static TenantUser toUserDetails(UserDto user, String homeTenantId) {
        return new TenantUser(
                user.username(),
                user.password(),
                user.active(),
                user.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList(),
                homeTenantId);
    }
}
