package es.brasatech.fastbite.service;

import es.brasatech.fastbite.application.fiscal.FiscalIssuerPort;
import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FiscalIssuerBackfillJobTest {

    @Test
    void skipsExecutionInSandboxEnvironment() {
        var issuers = mock(FiscalIssuerPort.class);
        var locations = mock(TenantLocationService.class);
        var profiles = mock(FiscalProfileService.class);

        var job = new FiscalIssuerBackfillJob(issuers, locations, profiles, new PlatformEnvironment("sandbox"), Clock.systemUTC());
        int result = job.run(Instant.now().plusSeconds(60));

        assertThat(result).isEqualTo(0);
        verifyNoInteractions(locations, profiles, issuers);
    }

    @Test
    void recordsValidNifsInProductionAndReturnsCount() {
        var issuers = mock(FiscalIssuerPort.class);
        var locations = mock(TenantLocationService.class);
        var profiles = mock(FiscalProfileService.class);

        TenantLocation loc1 = new TenantLocation("1", "owner1", "loc1", "RESTAURANT", null);
        TenantLocation loc2 = new TenantLocation("2", "owner2", "loc2", "RESTAURANT", null);
        when(locations.getAllLocations()).thenReturn(List.of(loc1, loc2));

        FiscalProfile prof1 = new FiscalProfile("Owner 1", "Bistro 1", "B12345674", "Calle 1", "28001", "Madrid", "Madrid", null, null, "B1", null);
        FiscalProfile prof2 = new FiscalProfile("Owner 2", "Bistro 2", "invalid-nif", "Calle 2", "28001", "Madrid", "Madrid", null, null, "B2", null);

        when(profiles.get()).thenReturn(prof1, prof2);
        when(issuers.recordIfAbsent(eq("B12345674"), eq("loc1"), any())).thenReturn(true);

        Instant fixedNow = Instant.parse("2026-10-26T12:00:00Z");
        Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);

        var job = new FiscalIssuerBackfillJob(issuers, locations, profiles, new PlatformEnvironment("production"), clock);
        int result = job.run(fixedNow.plusSeconds(60));

        assertThat(result).isEqualTo(1);
        verify(issuers).recordIfAbsent("B12345674", "loc1", fixedNow);
        verify(issuers, never()).recordIfAbsent(eq("invalid-nif"), any(), any());
    }

    @Test
    void respectsDeadline() {
        var issuers = mock(FiscalIssuerPort.class);
        var locations = mock(TenantLocationService.class);
        var profiles = mock(FiscalProfileService.class);

        when(locations.getAllLocations()).thenReturn(List.of(
                new TenantLocation("1", "owner1", "loc1", "RESTAURANT", null)
        ));

        Instant fixedNow = Instant.parse("2026-10-26T12:00:00Z");
        Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);

        var job = new FiscalIssuerBackfillJob(issuers, locations, profiles, new PlatformEnvironment("production"), clock);
        // Deadline in the past:
        int result = job.run(fixedNow.minusSeconds(1));

        assertThat(result).isEqualTo(0);
        verify(profiles, never()).get();
    }
}
