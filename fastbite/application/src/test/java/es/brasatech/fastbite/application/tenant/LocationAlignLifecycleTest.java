package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.application.mail.LocationLifecycleMailPort;
import es.brasatech.fastbite.application.platform.PendingSandboxDeletionPort;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.platform.SandboxDeletionSyncService;
import es.brasatech.fastbite.application.platform.SandboxLinkPort;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocationAlignLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void linkedSandboxDeletesLocallyWhenNoProductionCopyExists() {
        var env = new PlatformEnvironment("sandbox");
        var link = new PlatformLink(env, "https://prod.example", "https://prod.example", SECRET, false);
        var locations = mock(TenantLocationService.class);
        var deletion = mock(TenantDeletionPort.class);
        var billing = mock(BillingPort.class);
        var subs = mock(SubscriptionService.class);
        var backups = mock(TenantBackupRestorePort.class);
        var sandboxLink = mock(SandboxLinkPort.class);
        var mail = mock(LocationLifecycleMailPort.class);

        when(locations.isOwnerOf("owner@example.com", "bistro")).thenReturn(true);
        when(locations.getLocation("bistro")).thenReturn(Optional.of(new TenantLocation("1", "owner@example.com", "bistro", "PRO", null)));
        when(sandboxLink.fetchProductionLocationStatus("bistro", "owner@example.com"))
                .thenReturn(SandboxLinkPort.ProductionLocationStatus.ABSENT);

        var service = new LocationLifecycleService(
                locations, deletion, billing, subs, backups, env, link, sandboxLink, null, mail, FIXED_CLOCK, "Europe/Madrid");

        var result = service.deleteLocation("owner@example.com", "bistro", "bistro", "es");

        assertThat(result.outcome()).isEqualTo(LocationLifecycleService.DeleteResult.Outcome.DELETED);
        Instant expectedEraseAfter = NOW.plus(7, ChronoUnit.DAYS);
        assertThat(result.eraseAfter()).isEqualTo(expectedEraseAfter);
        verify(locations).markPendingDelete("bistro", "owner@example.com", NOW, expectedEraseAfter, null);
        verify(deletion, never()).deleteTenantCompletely(anyString());
        verify(mail, times(1)).sendLocationLifecycleNotice(
                "owner@example.com", "bistro",
                LocationLifecycleMailPort.Action.DELETED,
                LocationLifecycleMailPort.Scope.SANDBOX, NOW, expectedEraseAfter, "es");
    }

    @Test
    void linkedSandboxRedirectsToProductionWhenProductionCopyHasNoActivity() {
        var env = new PlatformEnvironment("sandbox");
        var link = new PlatformLink(env, "https://prod.example", "https://prod.example", SECRET, false);
        var locations = mock(TenantLocationService.class);
        var deletion = mock(TenantDeletionPort.class);
        var billing = mock(BillingPort.class);
        var subs = mock(SubscriptionService.class);
        var backups = mock(TenantBackupRestorePort.class);
        var sandboxLink = mock(SandboxLinkPort.class);
        var mail = mock(LocationLifecycleMailPort.class);

        when(locations.isOwnerOf("owner@example.com", "bistro")).thenReturn(true);
        when(sandboxLink.fetchProductionLocationStatus("bistro", "owner@example.com"))
                .thenReturn(new SandboxLinkPort.ProductionLocationStatus(true, false, false, false));

        var service = new LocationLifecycleService(
                locations, deletion, billing, subs, backups, env, link, sandboxLink, null, mail, FIXED_CLOCK, "Europe/Madrid");

        var result = service.deleteLocation("owner@example.com", "bistro", null, "es");

        assertThat(result.outcome()).isEqualTo(LocationLifecycleService.DeleteResult.Outcome.REDIRECT_TO_PRODUCTION);
        assertThat(result.redirectUrl())
                .isEqualTo("https://prod.example/owner/console?view=location-settings&section=general&location=bistro&confirmDelete=1");
        verify(deletion, never()).deleteTenantCompletely(anyString());
        verify(mail, never()).sendLocationLifecycleNotice(any(), any(), any(), any(), any(), any());
    }

    @Test
    void linkedSandboxRefusesWhenProductionCopyHasFiscalActivity() {
        var env = new PlatformEnvironment("sandbox");
        var link = new PlatformLink(env, "https://prod.example", "https://prod.example", SECRET, false);
        var locations = mock(TenantLocationService.class);
        var deletion = mock(TenantDeletionPort.class);
        var billing = mock(BillingPort.class);
        var subs = mock(SubscriptionService.class);
        var backups = mock(TenantBackupRestorePort.class);
        var sandboxLink = mock(SandboxLinkPort.class);
        var mail = mock(LocationLifecycleMailPort.class);

        when(locations.isOwnerOf("owner@example.com", "bistro")).thenReturn(true);
        when(sandboxLink.fetchProductionLocationStatus("bistro", "owner@example.com"))
                .thenReturn(new SandboxLinkPort.ProductionLocationStatus(true, false, true, false));

        var service = new LocationLifecycleService(
                locations, deletion, billing, subs, backups, env, link, sandboxLink, null, mail, FIXED_CLOCK, "Europe/Madrid");

        var result = service.deleteLocation("owner@example.com", "bistro", "bistro", "es");

        assertThat(result.outcome()).isEqualTo(LocationLifecycleService.DeleteResult.Outcome.PRODUCTION_HAS_TICKETS);
        assertThat(result.redirectUrl())
                .isEqualTo("https://prod.example/owner/console?view=location-settings&section=general&location=bistro");
        verify(deletion, never()).deleteTenantCompletely(anyString());
    }

    @Test
    void linkedSandboxRefusesWhenProductionIsUnreachable() {
        var env = new PlatformEnvironment("sandbox");
        var link = new PlatformLink(env, "https://prod.example", "https://prod.example", SECRET, false);
        var locations = mock(TenantLocationService.class);
        var deletion = mock(TenantDeletionPort.class);
        var billing = mock(BillingPort.class);
        var subs = mock(SubscriptionService.class);
        var backups = mock(TenantBackupRestorePort.class);
        var sandboxLink = mock(SandboxLinkPort.class);

        when(locations.isOwnerOf("owner@example.com", "bistro")).thenReturn(true);
        when(sandboxLink.fetchProductionLocationStatus("bistro", "owner@example.com"))
                .thenThrow(new SandboxLinkPort.Unavailable(SandboxLinkPort.Reason.UNREACHABLE, "timeout"));

        var service = new LocationLifecycleService(
                locations, deletion, billing, subs, backups, env, link, sandboxLink, null, null, FIXED_CLOCK, "Europe/Madrid");

        var result = service.deleteLocation("owner@example.com", "bistro", "bistro", "es");

        assertThat(result.outcome()).isEqualTo(LocationLifecycleService.DeleteResult.Outcome.PRODUCTION_UNREACHABLE);
        verify(deletion, never()).deleteTenantCompletely(anyString());
    }

    @Test
    void linkedProductionDeleteRemovesSandboxCopyAndSendsSingleEmail() {
        var env = new PlatformEnvironment("production");
        var link = new PlatformLink(env, "https://sandbox.example", "https://sandbox.example", SECRET, false);
        var locations = mock(TenantLocationService.class);
        var deletion = mock(TenantDeletionPort.class);
        var billing = mock(BillingPort.class);
        var subs = mock(SubscriptionService.class);
        var backups = mock(TenantBackupRestorePort.class);
        var sandboxLink = mock(SandboxLinkPort.class);
        var pending = mock(PendingSandboxDeletionPort.class);
        var sync = new SandboxDeletionSyncService(link, sandboxLink, pending, FIXED_CLOCK);
        var mail = mock(LocationLifecycleMailPort.class);

        when(locations.isOwnerOf("owner@example.com", "bistro")).thenReturn(true);
        when(locations.getLocation("bistro")).thenReturn(Optional.of(new TenantLocation("1", "owner@example.com", "bistro", "PRO", null)));

        var service = new LocationLifecycleService(
                locations, deletion, billing, subs, backups, env, link, sandboxLink, sync, mail, FIXED_CLOCK, "Europe/Madrid");

        var result = service.deleteLocation("owner@example.com", "bistro", "bistro", "es");

        assertThat(result.outcome()).isEqualTo(LocationLifecycleService.DeleteResult.Outcome.DELETED);
        Instant expectedEraseAfter = NOW.plus(7, ChronoUnit.DAYS);
        verify(locations).markPendingDelete(eq("bistro"), eq("owner@example.com"), eq(NOW), eq(expectedEraseAfter), any());
        verify(subs, never()).cancelSubscriptionImmediately(anyString(), anyString());
        verify(deletion, never()).deleteTenantCompletely(anyString());
        verify(sandboxLink).hideSandboxLocation("bistro", "owner@example.com", expectedEraseAfter);
        verify(pending).markDone("bistro");
        verify(mail, times(1)).sendLocationLifecycleNotice(
                "owner@example.com", "bistro",
                LocationLifecycleMailPort.Action.DELETED,
                LocationLifecycleMailPort.Scope.BOTH, NOW, expectedEraseAfter, "es");
    }

    @Test
    void linkedProductionCloseRemovesSandboxCopyAndSendsSingleEmail() {
        var env = new PlatformEnvironment("production");
        var link = new PlatformLink(env, "https://sandbox.example", "https://sandbox.example", SECRET, false);
        var locations = mock(TenantLocationService.class);
        var deletion = mock(TenantDeletionPort.class);
        var billing = mock(BillingPort.class);
        var subs = mock(SubscriptionService.class);
        var backups = mock(TenantBackupRestorePort.class);
        var sandboxLink = mock(SandboxLinkPort.class);
        var pending = mock(PendingSandboxDeletionPort.class);
        var sync = new SandboxDeletionSyncService(link, sandboxLink, pending, FIXED_CLOCK);
        var mail = mock(LocationLifecycleMailPort.class);

        when(locations.isOwnerOf("owner@example.com", "bistro")).thenReturn(true);
        when(locations.getLocation("bistro")).thenReturn(Optional.of(new TenantLocation("1", "owner@example.com", "bistro", "PRO", null)));

        var service = new LocationLifecycleService(
                locations, deletion, billing, subs, backups, env, link, sandboxLink, sync, mail, FIXED_CLOCK, "Europe/Madrid");

        var result = service.closeLocation("owner@example.com", "bistro", "bistro", "en");

        assertThat(result.outcome()).isEqualTo(LocationLifecycleService.CloseResult.Outcome.CLOSED);
        verify(subs).cancelSubscriptionAtPeriodEnd("bistro", "owner@example.com");
        verify(backups).cleanTenantData("bistro");
        verify(billing).closeLocationData("bistro", NOW);
        verify(sandboxLink).deleteSandboxLocation("bistro", "owner@example.com");
        verify(mail, times(1)).sendLocationLifecycleNotice(
                "owner@example.com", "bistro",
                LocationLifecycleMailPort.Action.CLOSED,
                LocationLifecycleMailPort.Scope.BOTH, NOW, "en");
    }

    @Test
    void failedSandboxDeleteEnqueuesAndRetriesIdempotently() {
        var env = new PlatformEnvironment("production");
        var link = new PlatformLink(env, "https://sandbox.example", "https://sandbox.example", SECRET, false);
        var sandboxLink = mock(SandboxLinkPort.class);
        var pendingList = new ArrayList<PendingSandboxDeletionPort.PendingDeletion>();
        PendingSandboxDeletionPort pending = new PendingSandboxDeletionPort() {
            @Override
            public void enqueue(String tenantId, String ownerUsername, Action action, String lastError, Instant now, Instant nextAttemptAt) {
                pendingList.removeIf(p -> p.tenantId().equals(tenantId));
                pendingList.add(new PendingDeletion(tenantId, ownerUsername, action, 1, nextAttemptAt, lastError));
            }

            @Override
            public List<PendingDeletion> due(Instant now, int limit) {
                return List.copyOf(pendingList);
            }

            @Override
            public void markDone(String tenantId) {
                pendingList.removeIf(p -> p.tenantId().equals(tenantId));
            }

            @Override
            public void reschedule(String tenantId, int attempts, Instant nextAttemptAt, String lastError, Instant now) {
                pendingList.removeIf(p -> p.tenantId().equals(tenantId));
                pendingList.add(new PendingDeletion(tenantId, "owner@example.com", attempts, nextAttemptAt, lastError));
            }
        };

        doThrow(new SandboxLinkPort.Unavailable(SandboxLinkPort.Reason.UNREACHABLE, "sandbox down"))
                .doNothing()
                .when(sandboxLink).deleteSandboxLocation("bistro", "owner@example.com");

        var sync = new SandboxDeletionSyncService(link, sandboxLink, pending, FIXED_CLOCK);

        assertThat(sync.deleteNowOrEnqueue("bistro", "owner@example.com")).isFalse();
        assertThat(pendingList).hasSize(1);
        assertThat(sync.sendDue()).isEqualTo(1);
        assertThat(pendingList).isEmpty();
    }

    @Test
    void ordersInProgressTodayBlockDeleteAndClose() {
        var env = new PlatformEnvironment("production");
        var link = new PlatformLink(env, "", "", "", true);
        var locations = mock(TenantLocationService.class);
        var deletion = mock(TenantDeletionPort.class);
        var billing = mock(BillingPort.class);
        var subs = mock(SubscriptionService.class);
        var backups = mock(TenantBackupRestorePort.class);

        when(locations.isOwnerOf("owner@example.com", "bistro")).thenReturn(true);
        when(deletion.hasOrdersInProgressToday(eq("bistro"), any(), any())).thenReturn(true);

        var service = new LocationLifecycleService(
                locations, deletion, billing, subs, backups, env, link, null, null, null, FIXED_CLOCK, "Europe/Madrid");

        assertThat(service.deleteLocation("owner@example.com", "bistro", "bistro").outcome())
                .isEqualTo(LocationLifecycleService.DeleteResult.Outcome.ORDERS_IN_PROGRESS);
        assertThat(service.closeLocation("owner@example.com", "bistro", "bistro").outcome())
                .isEqualTo(LocationLifecycleService.CloseResult.Outcome.ORDERS_IN_PROGRESS);
        verify(deletion, never()).deleteTenantCompletely(anyString());
        verify(billing, never()).closeLocationData(anyString(), any());
    }
}
