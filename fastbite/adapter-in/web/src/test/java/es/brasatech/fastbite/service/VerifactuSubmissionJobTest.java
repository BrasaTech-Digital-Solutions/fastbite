package es.brasatech.fastbite.service;

import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.VerifactuService;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

class VerifactuSubmissionJobTest {

    @Test
    void reportsMultipleOtWhenOwnerHasEverCreatedMultipleLocationsEvenIfOnlyOneIsCurrentlyActive() {
        var verifactu = mock(VerifactuService.class);
        when(verifactu.configured()).thenReturn(true);

        var locations = mock(TenantLocationService.class);
        // Only one active location remains in the platform for "maria"
        when(locations.getAllLocations()).thenReturn(List.of(
                new TenantLocation("1", "maria", "maria-bistro", "RESTAURANT", null)
        ));
        // But historically maria created 2 locations (one was closed/removed)
        when(locations.countLocationsEverCreatedByOwner()).thenReturn(Map.of("maria", 2L));

        var job = new VerifactuSubmissionJob(verifactu, locations, new PlatformEnvironment("production"), false, Clock.systemUTC());
        job.run(Instant.now().plusSeconds(60));

        // Must submit with multipleBillings = true (IndicadorMultiplesOT = 'S')
        verify(verifactu).submitPending(eq(false), eq(true));
    }

    @Test
    void reportsSingleOtWhenOwnerHasOnlyEverCreatedOneLocation() {
        var verifactu = mock(VerifactuService.class);
        when(verifactu.configured()).thenReturn(true);

        var locations = mock(TenantLocationService.class);
        when(locations.getAllLocations()).thenReturn(List.of(
                new TenantLocation("1", "pedro", "pedro-cafe", "RESTAURANT", null)
        ));
        when(locations.countLocationsEverCreatedByOwner()).thenReturn(Map.of("pedro", 1L));

        var job = new VerifactuSubmissionJob(verifactu, locations, new PlatformEnvironment("production"), false, Clock.systemUTC());
        job.run(Instant.now().plusSeconds(60));

        // Must submit with multipleBillings = false (IndicadorMultiplesOT = 'N')
        verify(verifactu).submitPending(eq(false), eq(false));
    }
}
