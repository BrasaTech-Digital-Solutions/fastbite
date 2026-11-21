package es.brasatech.fastbite.application.verifactu;

import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.mail.AccountMailOutboxPort;
import es.brasatech.fastbite.application.mail.AccountMailOutboxService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.tenant.OwnerWorkspacePort;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationGate.HoldReason;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.tenant.SetupProgress;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorization;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorizationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VerifactuNotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private TenantLocationService locations;
    private OwnerWorkspacePort onboarding;
    private FiscalProfileService fiscalProfiles;
    private VerifactuAuthorizationGate gate;
    private VerifactuAuthorizationStore authStore;
    private AccountMailOutboxPort outboxPort;
    private AccountMailOutboxService outboxService;
    private VerifactuNotificationService service;

    @BeforeEach
    void setUp() {
        locations = mock(TenantLocationService.class);
        onboarding = mock(OwnerWorkspacePort.class);
        fiscalProfiles = mock(FiscalProfileService.class);
        gate = mock(VerifactuAuthorizationGate.class);
        authStore = mock(VerifactuAuthorizationStore.class);
        outboxPort = mock(AccountMailOutboxPort.class);
        outboxService = mock(AccountMailOutboxService.class);
        service = new VerifactuNotificationService(
                new PlatformEnvironment("production"),
                locations, onboarding, fiscalProfiles, gate, authStore, outboxPort, outboxService, CLOCK);

        when(outboxPort.enqueueVerifactuNotification(anyString(), anyString(), any())).thenReturn(true);
        when(authStore.findValidByNif(anyString())).thenReturn(Optional.empty());
        when(authStore.findLatestByNif(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void newOnboardingLocationGetsNoOnHoldEmail() {
        TenantLocation loc = new TenantLocation("1", "ana", "newcafe", "RESTAURANT", null, null, false, false, null);
        when(locations.getLocation("newcafe")).thenReturn(Optional.of(loc));
        when(onboarding.read("newcafe")).thenReturn(SetupProgress.EMPTY);
        when(gate.holdReason("newcafe")).thenReturn(Optional.of(HoldReason.NOT_SIGNED));
        when(fiscalProfiles.get()).thenReturn(FiscalProfile.EMPTY);

        assertThat(service.evaluateLocation("newcafe", false)).isFalse();
        verifyNoInteractions(outboxPort, outboxService);
    }

    @Test
    void openedLocationLosingAuthorizationGetsOnHoldEmail() {
        TenantLocation loc = new TenantLocation("1", "ana", "openedcafe", "RESTAURANT", null, null, false, false, null);
        when(locations.getLocation("openedcafe")).thenReturn(Optional.of(loc));
        when(onboarding.read("openedcafe")).thenReturn(SetupProgress.EMPTY.withOpening(false, true));
        when(gate.holdReason("openedcafe")).thenReturn(Optional.of(HoldReason.COLLABORATION_INACTIVE));

        assertThat(service.evaluateLocation("openedcafe", false)).isTrue();
        verify(outboxPort).enqueueVerifactuNotification("openedcafe", VerifactuNotificationService.PURPOSE_ON_HOLD, NOW);
        verify(outboxService).runForLocation("openedcafe");
    }

    @Test
    void previouslyAuthorizedLocationLosingAuthorizationGetsOnHoldEmail() {
        TenantLocation loc = new TenantLocation("1", "ana", "revokedcafe", "RESTAURANT", null, null, false, false, null);
        when(locations.getLocation("revokedcafe")).thenReturn(Optional.of(loc));
        when(onboarding.read("revokedcafe")).thenReturn(SetupProgress.EMPTY);
        when(gate.holdReason("revokedcafe")).thenReturn(Optional.of(HoldReason.REVOKED));
        when(fiscalProfiles.get()).thenReturn(new FiscalProfile(
                "Cafe S.L.", "Cafe", "B12345674", "Calle 1", "28013", "Madrid", "Madrid", null, null, "CF", BigDecimal.TEN));
        VerifactuAuthorization revoked = new VerifactuAuthorization(
                UUID.randomUUID(), "B12345674", "Cafe S.L.", VerifactuAuthorizationStatus.REVOKED,
                "req_1", "key_1", 1, "tpl_1", "Ana", "B12345674",
                "/p/s.pdf", "aaa", "/p/e.pdf", "bbb", "REVOKED", "ana",
                NOW.minusSeconds(3600), NOW, NOW.minusSeconds(3000), NOW, NOW, 1, 0, null);
        when(authStore.findLatestByNif("B12345674")).thenReturn(Optional.of(revoked));

        assertThat(service.evaluateLocation("revokedcafe", false)).isTrue();
        verify(outboxPort).enqueueVerifactuNotification("revokedcafe", VerifactuNotificationService.PURPOSE_ON_HOLD, NOW);
    }

    @Test
    void firstOpeningWithTrialStartsTrialCopyAndFreePlanOmitsTrialCopy() {
        TenantLocation pro = new TenantLocation("1", "ana", "procafe", "RESTAURANT", null, null, false, false, null);
        TenantLocation free = new TenantLocation("2", "ana", "freecafe", "FREE", null, null, false, false, null);
        when(locations.getLocation("procafe")).thenReturn(Optional.of(pro));
        when(locations.getLocation("freecafe")).thenReturn(Optional.of(free));
        when(onboarding.read("procafe")).thenReturn(SetupProgress.EMPTY.withOpening(false, true));
        when(onboarding.read("freecafe")).thenReturn(SetupProgress.EMPTY.withOpening(false, true));
        when(gate.holdReason("procafe")).thenReturn(Optional.empty());
        when(gate.holdReason("freecafe")).thenReturn(Optional.empty());

        assertThat(service.evaluateLocation("procafe", true)).isTrue();
        verify(outboxPort).enqueueVerifactuNotification("procafe", VerifactuNotificationService.PURPOSE_OPENED_TRIAL, NOW);

        assertThat(service.evaluateLocation("freecafe", true)).isTrue();
        verify(outboxPort).enqueueVerifactuNotification("freecafe", VerifactuNotificationService.PURPOSE_OPENED_FREE, NOW);
    }

    @Test
    void evaluateForNifAndSweepNotifyMatchingNonClosedProductionLocations() {
        TenantLocation active = new TenantLocation("1", "ana", "activecafe", "RESTAURANT", null, null, false, false, null);
        TenantLocation closed = new TenantLocation("2", "ana", "closedcafe", "RESTAURANT", null, null, false, false, NOW);
        when(locations.getAllLocations()).thenReturn(List.of(active, closed));
        when(locations.getLocation("activecafe")).thenReturn(Optional.of(active));
        when(onboarding.read("activecafe")).thenReturn(SetupProgress.EMPTY.withOpening(false, true));
        when(gate.holdReason("activecafe")).thenReturn(Optional.of(HoldReason.COLLABORATION_INACTIVE));
        when(fiscalProfiles.get()).thenReturn(new FiscalProfile(
                "Active S.L.", "Active", "B12345674", "Calle 1", "28013", "Madrid", "Madrid", null, null, "AC", BigDecimal.TEN));

        assertThat(service.evaluateForNif("B12345674")).isEqualTo(1);
        assertThat(service.sweep()).isEqualTo(1);
        verify(outboxPort, never()).enqueueVerifactuNotification(eq("closedcafe"), anyString(), any());
    }
}
