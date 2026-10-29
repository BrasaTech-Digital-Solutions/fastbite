package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.application.office.UserService;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.user.Role;
import es.brasatech.fastbite.domain.user.UserDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OwnerStaffServiceTest {

    private final UserService userService = mock(UserService.class);
    private final TenantLocationService tenantLocationService = mock(TenantLocationService.class);
    private final OwnerStaffService service = new OwnerStaffService(userService, tenantLocationService);

    @BeforeEach
    void setUp() {
        when(tenantLocationService.isOwnerOf("alice", "pizza")).thenReturn(true);
        when(userService.findAll()).thenReturn(List.of());
    }

    @Test
    void createsStaffInsideTheOwnedTenant() {
        when(userService.findByUsername("cook1")).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            assertThat(TenantContext.getCurrentTenant()).isEqualTo("pizza");
            return null;
        }).when(userService).save(any());

        service.createStaff("alice", "pizza", "cook1", "Cook One", "hash", Role.COOK);

        verify(userService).save(new UserDto(null, "cook1", "hash", "Cook One", Set.of(Role.COOK), true, "pizza"));
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    @Test
    void refusesAnExistingUsernameInsteadOfOverwritingIt() {
        when(userService.findByUsername("bob")).thenReturn(Optional.of(
                new UserDto("1", "bob", "hash", "Bob", Set.of(Role.OWNER), true, null)));

        assertThatThrownBy(() -> service.createStaff("alice", "pizza", "bob", "Bob", "other", Role.ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already taken");
        verify(userService, never()).save(any());
    }

    @Test
    void refusesTheOwnerRoleForStaff() {
        assertThatThrownBy(() -> service.createStaff("alice", "pizza", "mallory", "M", "hash", Role.OWNER))
                .isInstanceOf(IllegalArgumentException.class);
        verify(userService, never()).save(any());
    }

    @Test
    void refusesTenantsTheCallerDoesNotOwn() {
        assertThatThrownBy(() -> service.createStaff("alice", "burger", "cook1", "Cook", "hash", Role.COOK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not own");
        verify(userService, never()).save(any());
    }
    @Test void newStaffUsernameCannotLookLikeAnEmail() {
        when(tenantLocationService.isOwnerOf("alice", "pizza")).thenReturn(true);
        assertThatThrownBy(() -> service.createStaff("alice", "pizza", "alice@example.test", "Alice", "hash", Role.COOK))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("without @");
    }
}
