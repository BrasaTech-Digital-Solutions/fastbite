package es.brasatech.fastbite.application.platform;

import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.tenant.TenantRegistrationPort;
import es.brasatech.fastbite.application.tenant.TenantSignupService;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LinkedLocationServiceTest {

    final TenantLocationService locations = mock(TenantLocationService.class);
    final TenantRegistrationPort registration = mock(TenantRegistrationPort.class);
    final TenantSignupService signup = mock(TenantSignupService.class);
    final LinkedLocationService service = new LinkedLocationService(locations, registration, signup);

    static final SandboxLinkPort.LinkedLocation LOCATION =
            new SandboxLinkPort.LinkedLocation("anacafe", "ana", "Ana García", "RESTAURANT");

    @Test
    void anOwnerNewToTheSandboxIsCreatedWithTheLocationAndNoPassword() {
        when(locations.getLocation("anacafe")).thenReturn(Optional.empty());
        doThrow(new IllegalArgumentException("An active owner account is required.")).when(registration).requireActiveOwner("ana");

        service.receive(LOCATION, "{bcrypt}unknown");

        verify(signup).registerTenant("anacafe", "ana", "{bcrypt}unknown", "Ana García", "FREE");
    }

    @Test
    void anOwnerAlreadyInTheSandboxGetsTheNewLocation() {
        when(locations.getLocation("anacafe")).thenReturn(Optional.empty());

        service.receive(LOCATION, "{bcrypt}unknown");

        verify(signup).registerAdditionalLocation("anacafe", "ana", "FREE", "link:anacafe");
        verify(signup, never()).registerTenant(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aLocationAlreadyThereIsLeftAsItIs() {
        when(locations.getLocation("anacafe")).thenReturn(Optional.of(new TenantLocation("1", "ana", "anacafe", "FREE", null)));

        service.receive(LOCATION, "{bcrypt}unknown");

        verify(signup, never()).registerAdditionalLocation(any(), any(), any(), any());
        verify(signup, never()).registerTenant(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aLocationOfAnotherOwnerIsRefused() {
        when(locations.getLocation("anacafe")).thenReturn(Optional.of(new TenantLocation("1", "luis", "anacafe", "FREE", null)));

        assertThatThrownBy(() -> service.receive(LOCATION, "{bcrypt}unknown")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another owner");
    }

    @Test
    void concurrentSetupThatCompletedForSameOwnerSucceedsIdempotently() {
        when(locations.getLocation("anacafe"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new TenantLocation("1", "ana", "anacafe", "FREE", null)));
        doThrow(new es.brasatech.fastbite.domain.tenant.TenantProvisioningInProgressException("in progress"))
                .when(signup).registerAdditionalLocation(anyString(), anyString(), anyString(), anyString());

        service.receive(LOCATION, "{bcrypt}unknown");
    }

    @Test
    void concurrentSetupStillInProgressPropagatesException() {
        when(locations.getLocation("anacafe"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        doThrow(new es.brasatech.fastbite.domain.tenant.TenantProvisioningInProgressException("in progress"))
                .when(signup).registerAdditionalLocation(anyString(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> service.receive(LOCATION, "{bcrypt}unknown"))
                .isInstanceOf(es.brasatech.fastbite.domain.tenant.TenantProvisioningInProgressException.class);
    }
}
