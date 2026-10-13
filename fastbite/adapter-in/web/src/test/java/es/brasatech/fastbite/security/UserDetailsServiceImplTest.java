package es.brasatech.fastbite.security;

import es.brasatech.fastbite.application.office.UserService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.user.Role;
import es.brasatech.fastbite.domain.user.UserDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserDetailsServiceImplTest {

    private static final UserDto OWNER = new UserDto("1", "alice", "hash", "Alice", Set.of(Role.OWNER, Role.ADMIN), true, "pizza");
    private static final UserDto PIZZA_COOK = new UserDto("2", "carl", "hash", "Carl", Set.of(Role.COOK), true, "pizza");

    private final UserService userService = mock(UserService.class);
    private final TenantLocationService tenantLocationService = mock(TenantLocationService.class);
    private static final PlatformEnvironment PRODUCTION = new PlatformEnvironment("production");
    private final UserDetailsServiceImpl service = new UserDetailsServiceImpl(userService, tenantLocationService,
            new PlatformLink(PRODUCTION, "http://sandbox", "", "a-secret-of-at-least-thirty-two-characters", false));

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Answers from the platform schema when no tenant is set, otherwise from the tenant's schema. */
    private void users(UserDto platformUser, UserDto tenantUser) {
        when(userService.findByUsername(anyString())).thenAnswer(invocation -> {
            String username = invocation.getArgument(0);
            UserDto user = TenantContext.getCurrentTenant() == null ? platformUser : tenantUser;
            return Optional.ofNullable(user).filter(u -> u.username().equals(username));
        });
    }

    @Test
    void signsInPlatformAccountsOutsideRestaurants() {
        users(OWNER, null);

        TenantUser user = (TenantUser) service.loadUserByUsername("alice");

        assertThat(user.isPlatformAccount()).isTrue();
    }

    @Test
    void signsInOwnersAsPlatformAccountsInTheirOwnRestaurant() {
        users(OWNER, null);
        when(tenantLocationService.isOwnerOf("alice", "pizza")).thenReturn(true);
        TenantContext.setCurrentTenant("pizza");

        TenantUser user = (TenantUser) service.loadUserByUsername("alice");

        assertThat(user.isPlatformAccount()).isTrue();
        assertThat(TenantContext.getCurrentTenant()).isEqualTo("pizza");
    }

    @Test
    void signsInStaffAgainstTheCurrentRestaurant() {
        users(null, PIZZA_COOK);
        TenantContext.setCurrentTenant("pizza");

        TenantUser user = (TenantUser) service.loadUserByUsername("carl");

        assertThat(user.belongsTo("pizza")).isTrue();
        assertThat(user.getAuthorities()).extracting("authority").containsExactly("ROLE_COOK");
    }

    @Test
    void platformAccountsThatDoNotOwnTheRestaurantCannotSignInThere() {
        users(OWNER, null);
        TenantContext.setCurrentTenant("burger");

        assertThatThrownBy(() -> service.loadUserByUsername("alice")).isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void aLinkedSandboxTakesNoOwnersPasswordButStillSignsInItsStaff() {
        var sandbox = new PlatformEnvironment("sandbox");
        var linkedSandbox = new UserDetailsServiceImpl(userService, tenantLocationService,
                new PlatformLink(sandbox, "http://production", "", "a-secret-of-at-least-thirty-two-characters", false));
        users(OWNER, PIZZA_COOK);
        when(tenantLocationService.isOwnerOf("alice", "pizza")).thenReturn(true);

        assertThatThrownBy(() -> linkedSandbox.loadUserByUsername("alice")).isInstanceOf(UsernameNotFoundException.class);
        TenantContext.setCurrentTenant("pizza");
        assertThatThrownBy(() -> linkedSandbox.loadUserByUsername("alice")).isInstanceOf(UsernameNotFoundException.class);
        assertThat(((TenantUser) linkedSandbox.loadUserByUsername("carl")).belongsTo("pizza")).isTrue();
    }
}
