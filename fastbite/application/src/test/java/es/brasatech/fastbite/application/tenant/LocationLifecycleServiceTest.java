package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.domain.tenant.BillingAccount;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocationLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void sandboxDeleteRemovesCompletelyWithoutTouchingBillingOrProduction() {
        TenantLocationService locations = mock(TenantLocationService.class);
        TenantDeletionPort deletionPort = mock(TenantDeletionPort.class);
        BillingPort billing = mock(BillingPort.class);
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        TenantBackupRestorePort backups = mock(TenantBackupRestorePort.class);

        when(locations.isOwnerOf("ana", "practicecafe")).thenReturn(true);
        when(locations.isClosed("practicecafe")).thenReturn(false);
        when(subscriptions.isClosed("practicecafe")).thenReturn(false);
        when(locations.getLocation("practicecafe")).thenReturn(Optional.of(
                new TenantLocation("1", "ana", "practicecafe", "RESTAURANT", null, null)));

        LocationLifecycleService service = new LocationLifecycleService(
                locations, deletionPort, billing, subscriptions, backups,
                new PlatformEnvironment("sandbox"), FIXED_CLOCK);

        assertThat(service.removalAction("ana", "practicecafe"))
                .isEqualTo(LocationLifecycleService.RemovalAction.SANDBOX_DELETE);

        LocationLifecycleService.DeleteResult result = service.deleteLocation("ana", "practicecafe", "practicecafe");
        assertThat(result.outcome()).isEqualTo(LocationLifecycleService.DeleteResult.Outcome.DELETED);
        assertThat(result.eraseAfter()).isEqualTo(NOW.plus(7, ChronoUnit.DAYS));

        verify(locations).markPendingDelete("practicecafe", "ana", NOW, NOW.plus(7, ChronoUnit.DAYS));
        verify(deletionPort, never()).deleteTenantCompletely(any());
        verify(subscriptions, never()).cancelSubscriptionImmediately(any(), any());
        verify(billing, never()).closeLocationData(any(), any());

        LocationLifecycleService.CloseResult closeResult = service.closeLocation("ana", "practicecafe", "practicecafe");
        assertThat(closeResult.outcome()).isEqualTo(LocationLifecycleService.CloseResult.Outcome.SANDBOX_USE_DELETE);
    }

    @Test
    void productionWithoutTicketsHidesFirstAndCancelsStripeOnlyAtRealErase() {
        TenantLocationService locations = mock(TenantLocationService.class);
        TenantDeletionPort deletionPort = mock(TenantDeletionPort.class);
        BillingPort billing = mock(BillingPort.class);
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        TenantBackupRestorePort backups = mock(TenantBackupRestorePort.class);
        LocationNameHoldPort nameHolds = mock(LocationNameHoldPort.class);

        when(locations.isOwnerOf("ana", "newbistro")).thenReturn(true);
        when(locations.isClosed("newbistro")).thenReturn(false);
        when(subscriptions.isClosed("newbistro")).thenReturn(false);
        when(deletionPort.hasFiscalOrPaidActivity("newbistro")).thenReturn(false);
        when(locations.getLocation("newbistro")).thenReturn(Optional.of(
                new TenantLocation("2", "ana", "newbistro", "RESTAURANT", "orders.newbistro.test", null)));

        LocationLifecycleService service = new LocationLifecycleService(
                locations, deletionPort, billing, subscriptions, backups,
                new PlatformEnvironment("production"), null, null, null, null, nameHolds,
                FIXED_CLOCK, "Europe/Madrid", 7, 90);

        assertThat(service.removalAction("ana", "newbistro"))
                .isEqualTo(LocationLifecycleService.RemovalAction.PRODUCTION_DELETE);

        LocationLifecycleService.DeleteResult result = service.deleteLocation("ana", "newbistro", "  NEWBISTRO  ");
        assertThat(result.outcome()).isEqualTo(LocationLifecycleService.DeleteResult.Outcome.DELETED);
        assertThat(result.customDomain()).isEqualTo("orders.newbistro.test");
        assertThat(result.eraseAfter()).isEqualTo(NOW.plus(7, ChronoUnit.DAYS));

        verify(locations).markPendingDelete("newbistro", "ana", NOW, NOW.plus(7, ChronoUnit.DAYS));
        verify(subscriptions, never()).cancelSubscriptionImmediately(any(), any());
        verify(deletionPort, never()).deleteTenantCompletely(any());

        // Real erase after 7 days cancels Stripe, records 90-day name hold, and deletes completely
        Instant afterSevenDays = NOW.plus(7, ChronoUnit.DAYS);
        TenantLocation due = new TenantLocation("2", "ana", "newbistro", "RESTAURANT", "orders.newbistro.test",
                null, false, false, null, NOW, afterSevenDays);
        when(locations.findDueForErase(afterSevenDays)).thenReturn(List.of(due));

        LocationLifecycleService eraseService = new LocationLifecycleService(
                locations, deletionPort, billing, subscriptions, backups,
                new PlatformEnvironment("production"), null, null, null, null, nameHolds,
                Clock.fixed(afterSevenDays, ZoneOffset.UTC), "Europe/Madrid", 7, 90);

        assertThat(eraseService.eraseDueLocations()).isEqualTo(1);
        verify(subscriptions).cancelSubscriptionImmediately("newbistro", "ana");
        verify(nameHolds).hold("newbistro", "ana", afterSevenDays.plus(90, ChronoUnit.DAYS), afterSevenDays);
        verify(deletionPort).deleteTenantCompletely("newbistro");
        verify(nameHolds).purgeExpired(afterSevenDays);
    }

    @Test
    void undoRestoresWithinWindowAndRefusesAfterSevenDays() {
        TenantLocationService locations = mock(TenantLocationService.class);
        TenantDeletionPort deletionPort = mock(TenantDeletionPort.class);
        BillingPort billing = mock(BillingPort.class);
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        TenantBackupRestorePort backups = mock(TenantBackupRestorePort.class);

        Instant eraseAfter = NOW.plus(7, ChronoUnit.DAYS);
        TenantLocation hidden = new TenantLocation("2", "ana", "newbistro", "RESTAURANT", "orders.newbistro.test",
                null, false, false, null, NOW, eraseAfter);
        when(locations.isOwnerOf("ana", "newbistro")).thenReturn(true);
        when(locations.getLocation("newbistro")).thenReturn(Optional.of(hidden));

        // Within 7 days -> restored
        LocationLifecycleService withinWindow = new LocationLifecycleService(
                locations, deletionPort, billing, subscriptions, backups,
                new PlatformEnvironment("production"), Clock.fixed(NOW.plus(6, ChronoUnit.DAYS), ZoneOffset.UTC));
        var restored = withinWindow.undoDelete("ana", "newbistro");
        assertThat(restored.outcome()).isEqualTo(LocationLifecycleService.UndoResult.Outcome.RESTORED);
        assertThat(restored.customDomain()).isEqualTo("orders.newbistro.test");
        verify(locations).clearPendingDelete("newbistro");

        // At/after 7 days -> refused
        LocationLifecycleService expiredWindow = new LocationLifecycleService(
                locations, deletionPort, billing, subscriptions, backups,
                new PlatformEnvironment("production"), Clock.fixed(eraseAfter, ZoneOffset.UTC));
        var expired = expiredWindow.undoDelete("ana", "newbistro");
        assertThat(expired.outcome()).isEqualTo(LocationLifecycleService.UndoResult.Outcome.UNDO_WINDOW_EXPIRED);
    }

    @Test
    void sandboxOnlyEraseFreesNameImmediatelyWithoutNameHold() {
        TenantLocationService locations = mock(TenantLocationService.class);
        TenantDeletionPort deletionPort = mock(TenantDeletionPort.class);
        BillingPort billing = mock(BillingPort.class);
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        TenantBackupRestorePort backups = mock(TenantBackupRestorePort.class);
        LocationNameHoldPort nameHolds = mock(LocationNameHoldPort.class);

        Instant eraseAfter = NOW.plus(7, ChronoUnit.DAYS);
        TenantLocation due = new TenantLocation("1", "ana", "practicecafe", "RESTAURANT", null,
                null, false, false, null, NOW, eraseAfter);
        when(locations.findDueForErase(eraseAfter)).thenReturn(List.of(due));

        LocationLifecycleService sandboxErase = new LocationLifecycleService(
                locations, deletionPort, billing, subscriptions, backups,
                new PlatformEnvironment("sandbox"), null, null, null, null, nameHolds,
                Clock.fixed(eraseAfter, ZoneOffset.UTC), "Europe/Madrid", 7, 90);

        assertThat(sandboxErase.eraseDueLocations()).isEqualTo(1);
        verify(deletionPort).deleteTenantCompletely("practicecafe");
        verify(subscriptions, never()).cancelSubscriptionImmediately(any(), any());
        verify(nameHolds, never()).hold(any(), any(), any(), any());
    }

    @Test
    void productionWithTicketsRefusesDeleteAndClosesCleanly() {
        TenantLocationService locations = mock(TenantLocationService.class);
        TenantDeletionPort deletionPort = mock(TenantDeletionPort.class);
        BillingPort billing = mock(BillingPort.class);
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        TenantBackupRestorePort backups = mock(TenantBackupRestorePort.class);

        when(locations.isOwnerOf("ana", "realbistro")).thenReturn(true);
        when(locations.isClosed("realbistro")).thenReturn(false);
        when(subscriptions.isClosed("realbistro")).thenReturn(false);
        when(deletionPort.hasFiscalOrPaidActivity("realbistro")).thenReturn(true);
        when(locations.getLocation("realbistro")).thenReturn(Optional.of(
                new TenantLocation("3", "ana", "realbistro", "RESTAURANT", "menu.realbistro.test", null)));

        BillingAccount initial = new BillingAccount(
                "realbistro", "ana", "key", Instant.parse("2026-10-01T00:00:00Z"),
                "active", "cus_123", "sub_123", 1800000000L, 0, false,
                null, null, 0, 0, null, false);
        AtomicReference<BillingAccount> updatedHolder = new AtomicReference<>();
        when(billing.updateLocked(eq("realbistro"), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            UnaryOperator<BillingAccount> fn = inv.getArgument(1);
            BillingAccount updated = fn.apply(initial);
            updatedHolder.set(updated);
            return updated;
        });

        LocationLifecycleService service = new LocationLifecycleService(
                locations, deletionPort, billing, subscriptions, backups,
                new PlatformEnvironment("production"), FIXED_CLOCK);

        assertThat(service.removalAction("ana", "realbistro"))
                .isEqualTo(LocationLifecycleService.RemovalAction.PRODUCTION_CLOSE);

        LocationLifecycleService.DeleteResult deleteResult = service.deleteLocation("ana", "realbistro", "realbistro");
        assertThat(deleteResult.outcome()).isEqualTo(LocationLifecycleService.DeleteResult.Outcome.HAS_TICKETS);
        verify(deletionPort, never()).deleteTenantCompletely(any());
        verify(subscriptions, never()).cancelSubscriptionImmediately(any(), any());

        LocationLifecycleService.CloseResult closeResult = service.closeLocation("ana", "realbistro", "realbistro");
        assertThat(closeResult.outcome()).isEqualTo(LocationLifecycleService.CloseResult.Outcome.CLOSED);
        assertThat(closeResult.customDomain()).isEqualTo("menu.realbistro.test");

        verify(subscriptions).cancelSubscriptionAtPeriodEnd("realbistro", "ana");
        verify(backups).cleanTenantData("realbistro");
        verify(billing).closeLocationData("realbistro", NOW);
        assertThat(updatedHolder.get()).isNotNull();
        assertThat(updatedHolder.get().isClosed()).isTrue();
        assertThat(updatedHolder.get().closedAt()).isEqualTo(NOW);
    }

    @Test
    void nameMismatchAndNonOwnerAndClosedAreRejected() {
        TenantLocationService locations = mock(TenantLocationService.class);
        TenantDeletionPort deletionPort = mock(TenantDeletionPort.class);
        BillingPort billing = mock(BillingPort.class);
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        TenantBackupRestorePort backups = mock(TenantBackupRestorePort.class);

        when(locations.isOwnerOf("ana", "mycafe")).thenReturn(true);
        when(locations.isOwnerOf("other", "mycafe")).thenReturn(false);

        LocationLifecycleService service = new LocationLifecycleService(
                locations, deletionPort, billing, subscriptions, backups,
                new PlatformEnvironment("production"), FIXED_CLOCK);

        assertThatThrownBy(() -> service.removalAction("other", "mycafe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.deleteLocation("other", "mycafe", "mycafe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.closeLocation("other", "mycafe", "mycafe"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(service.deleteLocation("ana", "mycafe", "wrong").outcome())
                .isEqualTo(LocationLifecycleService.DeleteResult.Outcome.NAME_MISMATCH);
        assertThat(service.closeLocation("ana", "mycafe", "").outcome())
                .isEqualTo(LocationLifecycleService.CloseResult.Outcome.NAME_MISMATCH);

        when(locations.isClosed("mycafe")).thenReturn(true);
        assertThatThrownBy(() -> service.deleteLocation("ana", "mycafe", "mycafe"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.closeLocation("ana", "mycafe", "mycafe"))
                .isInstanceOf(IllegalStateException.class);
    }
}
