package es.brasatech.fastbite.application.verifactu;

import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationGate.HoldReason;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorization;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorizationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerifactuAuthorizationGateTest {

    private static final FiscalProfile VALID_PROFILE = new FiscalProfile(
            "Kebab House SL", "Kebab House", "B09767898",
            "Calle Mayor 1", "28013", "Madrid", "Madrid",
            "600123456", "owner@kebab.es", "KH", new BigDecimal("10"));

    private FiscalProfileService fiscalProfiles;
    private VerifactuAuthorizationService authorizations;

    @BeforeEach
    void setUp() {
        fiscalProfiles = mock(FiscalProfileService.class);
        authorizations = mock(VerifactuAuthorizationService.class);
        when(fiscalProfiles.get()).thenReturn(VALID_PROFILE);
        when(authorizations.isSigningConfigured()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void sandboxAlwaysAllowsIssuingTicketsEvenWithoutFiscalProfileOrAuthorisation() {
        when(fiscalProfiles.get()).thenReturn(FiscalProfile.EMPTY);
        when(authorizations.isCollaborationActive()).thenReturn(false);
        when(authorizations.hasValidAuthorization("B09767898")).thenReturn(false);

        VerifactuAuthorizationGate gate = new VerifactuAuthorizationGate(
                new PlatformEnvironment("sandbox"), fiscalProfiles, authorizations);

        assertThat(gate.mayIssueTickets("kebab")).isTrue();
        assertThat(gate.holdReason("kebab")).isEmpty();
        gate.requireMayIssueTickets("kebab");
    }

    @Test
    void productionAllowsOnlyWhenCollaborationActiveValidNifAndValidAuthorisation() {
        when(authorizations.isCollaborationActive()).thenReturn(true);
        when(authorizations.hasValidAuthorization("B09767898")).thenReturn(true);

        VerifactuAuthorizationGate gate = new VerifactuAuthorizationGate(
                new PlatformEnvironment("production"), fiscalProfiles, authorizations);

        assertThat(gate.mayIssueTickets("bistro")).isTrue();
        assertThat(gate.holdReason("bistro")).isEmpty();
        gate.requireMayIssueTickets("bistro");
    }

    @Test
    void productionBlocksWhenCollaborationSwitchIsOffEvenWithValidAuthorisation() {
        when(authorizations.isCollaborationActive()).thenReturn(false);
        when(authorizations.hasValidAuthorization("B09767898")).thenReturn(true);

        VerifactuAuthorizationGate gate = new VerifactuAuthorizationGate(
                new PlatformEnvironment("production"), fiscalProfiles, authorizations);

        assertThat(gate.mayIssueTickets("bistro")).isFalse();
        assertThat(gate.holdReason("bistro")).contains(HoldReason.COLLABORATION_INACTIVE);
        assertThatThrownBy(() -> gate.requireMayIssueTickets("bistro"))
                .isInstanceOf(VerifactuAuthorizationRequiredException.class)
                .satisfies(ex -> {
                    var verr = (VerifactuAuthorizationRequiredException) ex;
                    assertThat(verr.reason()).isEqualTo(HoldReason.COLLABORATION_INACTIVE);
                    assertThat(verr.code()).isEqualTo("VERIFACTU_AUTHORIZATION_REQUIRED");
                });
    }

    @Test
    void productionHasNoBypassForKebabTenant() {
        when(authorizations.isCollaborationActive()).thenReturn(true);
        when(authorizations.hasValidAuthorization("B09767898")).thenReturn(false);
        when(authorizations.findCurrentForNif("B09767898")).thenReturn(Optional.empty());

        VerifactuAuthorizationGate gate = new VerifactuAuthorizationGate(
                new PlatformEnvironment("production"), fiscalProfiles, authorizations);

        assertThat(gate.mayIssueTickets("kebab")).isFalse();
        assertThat(gate.holdReason("kebab")).contains(HoldReason.NOT_SIGNED);
    }

    @Test
    void productionMapsRevokedAndIncompleteFiscalToHoldReasons() {
        when(authorizations.isCollaborationActive()).thenReturn(true);
        when(authorizations.hasValidAuthorization("B09767898")).thenReturn(false);
        Instant now = Instant.parse("2026-11-20T10:00:00Z");
        VerifactuAuthorization revoked = new VerifactuAuthorization(
                UUID.randomUUID(), "B09767898", "Kebab House SL", VerifactuAuthorizationStatus.REVOKED,
                "req-1", "verifactu-B09767898-1", 1, "tpl-1", "Ana", "B09767898",
                "p1", "sha1", "p2", "sha2", null, "ana", now, now, now, now.plusSeconds(60), now, 1);
        when(authorizations.findCurrentForNif("B09767898")).thenReturn(Optional.of(revoked));

        VerifactuAuthorizationGate gate = new VerifactuAuthorizationGate(
                new PlatformEnvironment("production"), fiscalProfiles, authorizations);

        assertThat(gate.mayIssueTickets("bistro")).isFalse();
        assertThat(gate.holdReason("bistro")).contains(HoldReason.REVOKED);

        when(fiscalProfiles.get()).thenReturn(FiscalProfile.EMPTY);
        assertThat(gate.holdReason("bistro")).contains(HoldReason.FISCAL_INCOMPLETE);
    }

    @Test
    void holdReasonSwitchesAndRestoresTenantContext() {
        TenantContext.setCurrentTenant("other-tenant");
        when(fiscalProfiles.get()).thenAnswer(inv -> {
            assertThat(TenantContext.getCurrentTenant()).isEqualTo("target-tenant");
            return VALID_PROFILE;
        });
        when(authorizations.isCollaborationActive()).thenReturn(true);
        when(authorizations.hasValidAuthorization("B09767898")).thenReturn(true);

        VerifactuAuthorizationGate gate = new VerifactuAuthorizationGate(
                new PlatformEnvironment("production"), fiscalProfiles, authorizations);

        assertThat(gate.mayIssueTickets("target-tenant")).isTrue();
        assertThat(TenantContext.getCurrentTenant()).isEqualTo("other-tenant");
    }
}
