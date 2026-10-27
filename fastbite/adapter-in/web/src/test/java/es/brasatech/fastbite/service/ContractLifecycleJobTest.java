package es.brasatech.fastbite.service;

import es.brasatech.fastbite.application.mail.ContractLifecycleMailPort;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.tenant.BillingPort;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.domain.tenant.BillingAccount;
import es.brasatech.fastbite.domain.tenant.PlatformBillingSettings;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ContractLifecycleJobTest {

    private AtomicReference<Instant> currentInstant;
    private Clock clock;
    private InMemoryBillingPort billing;
    private TenantLocationService locations;
    private TenantBackupRestorePort backups;
    private ContractLifecycleMailPort mail;
    private TenantRoutingResolver routingResolver;
    private ContractLifecycleJob job;

    @BeforeEach
    void setUp() {
        currentInstant = new AtomicReference<>(Instant.parse("2026-10-01T10:00:00Z"));
        clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return currentInstant.get(); }
        };
        billing = new InMemoryBillingPort();
        locations = mock(TenantLocationService.class);
        backups = mock(TenantBackupRestorePort.class);
        mail = mock(ContractLifecycleMailPort.class);
        routingResolver = new TenantRoutingResolver(locations);
        job = new ContractLifecycleJob(billing, locations, backups, mail, routingResolver,
                new PlatformEnvironment("production"), false, true, clock);
    }

    @Test
    void fullLifecycleFromTrialPauseThroughWarningContractEndAndDay60Deletion() {
        Instant trialStartedAt = Instant.parse("2026-09-01T10:00:00Z"); // trialEndsAt = 2026-10-01T10:00:00Z
        Instant expectedPausedSince = trialStartedAt.plus(30, ChronoUnit.DAYS);
        Instant expectedContractEndsAt = expectedPausedSince.plus(30, ChronoUnit.DAYS); // 2026-10-31T10:00:00Z

        billing.put(new BillingAccount("bistro", "owner@bistro.es", "key-1", trialStartedAt,
                "LOCAL_TRIAL", null, null, 0, 0, false, null, null, 0, 0), "fiscal@bistro.es");
        when(locations.getLocation("bistro")).thenReturn(
                Optional.of(new TenantLocation("1", "owner@bistro.es", "bistro", "RESTAURANT", "bistro.example.es")));

        // 1. Day 0 of pause (trial just expired): sends Email 1 (orders paused) and records paused_since & pause_notified_at
        assertThat(job.run(deadline())).isEqualTo(1);
        verify(mail, times(1)).sendOrdersPausedNotice("fiscal@bistro.es", "bistro", expectedPausedSince, expectedContractEndsAt);
        BillingAccount afterPause = billing.find("bistro").orElseThrow();
        assertThat(afterPause.pausedSince()).isEqualTo(expectedPausedSince);
        assertThat(afterPause.pauseNotifiedAt()).isEqualTo(currentInstant.get());
        assertThat(afterPause.warningNotifiedAt()).isNull();
        assertThat(afterPause.contractEndedAt()).isNull();
        assertThat(afterPause.closedAt()).isNull();

        // Repeated run an hour later is idempotent (no duplicate email)
        currentInstant.set(currentInstant.get().plus(1, ChronoUnit.HOURS));
        assertThat(job.run(deadline())).isZero();
        verifyNoMoreInteractions(mail);

        // 2. Day 23 of pause (7 days before contract end): sends Email 2 (7-day warning)
        currentInstant.set(expectedContractEndsAt.minus(7, ChronoUnit.DAYS));
        assertThat(job.run(deadline())).isEqualTo(1);
        verify(mail, times(1)).sendContractEndingWarning("fiscal@bistro.es", "bistro", expectedContractEndsAt);
        BillingAccount afterWarning = billing.find("bistro").orElseThrow();
        assertThat(afterWarning.warningNotifiedAt()).isEqualTo(currentInstant.get());
        assertThat(afterWarning.contractEndedAt()).isNull();

        // Repeated run during the warning window is idempotent
        currentInstant.set(currentInstant.get().plus(2, ChronoUnit.DAYS));
        assertThat(job.run(deadline())).isZero();
        verifyNoMoreInteractions(mail);

        // 3. Day 30 of pause: contract ends, sends Email 3 (contract ended, 30 days to export)
        currentInstant.set(expectedContractEndsAt);
        Instant expectedDeletionAt = expectedContractEndsAt.plus(30, ChronoUnit.DAYS);
        assertThat(job.run(deadline())).isEqualTo(1);
        verify(mail, times(1)).sendContractEndedNotice("fiscal@bistro.es", "bistro", expectedContractEndsAt, expectedDeletionAt);
        BillingAccount afterEnd = billing.find("bistro").orElseThrow();
        assertThat(afterEnd.contractEndedAt()).isEqualTo(expectedContractEndsAt);
        assertThat(afterEnd.closedAt()).isNull();
        assertThat(afterEnd.canTakeOrders(currentInstant.get(), true)).isFalse();
        assertThat(afterEnd.displayStatus()).isEqualTo("Contract ended");

        // During the 30-day export window (e.g. day 45), data is NOT deleted yet
        currentInstant.set(expectedContractEndsAt.plus(15, ChronoUnit.DAYS));
        assertThat(job.run(deadline())).isZero();
        verify(backups, never()).cleanTenantData(anyString());
        assertThat(billing.closedLocations).isEmpty();

        // 4. Day 60 (30 days after contract end): deletes non-fiscal data and permanently closes the location
        currentInstant.set(expectedDeletionAt);
        assertThat(job.run(deadline())).isEqualTo(1);
        verify(backups, times(1)).cleanTenantData("bistro");
        assertThat(billing.closedLocations).containsKey("bistro");
        BillingAccount afterClose = billing.find("bistro").orElseThrow();
        assertThat(afterClose.isClosed()).isTrue();
        assertThat(afterClose.closingStartedAt()).isEqualTo(expectedDeletionAt);
        assertThat(afterClose.closedAt()).isEqualTo(expectedDeletionAt);
        assertThat(afterClose.canTakeOrders(currentInstant.get(), true)).isFalse();

        // Subsequent runs skip the closed location
        currentInstant.set(expectedDeletionAt.plus(1, ChronoUnit.DAYS));
        assertThat(job.run(deadline())).isZero();
        verify(backups, times(1)).cleanTenantData("bistro");
    }

    @Test
    void locationPaused90DaysBeforeFirstObservationStarts30DayClockAtFirstObservation() {
        Instant t0 = currentInstant.get(); // 2026-10-01T10:00:00Z
        // Trial started 120 days ago, so trial ended 90 days before first observation
        Instant trialStartedAt = t0.minus(120, ChronoUnit.DAYS);
        Instant pausedSince = trialStartedAt.plus(30, ChronoUnit.DAYS); // t0 - 90 days
        Instant expectedContractEndsAt = t0.plus(30, ChronoUnit.DAYS);  // max(pausedSince, t0) + 30 days

        billing.put(new BillingAccount("legacy", "owner@legacy.es", "key-leg", trialStartedAt,
                "LOCAL_TRIAL", null, null, 0, 0, false, null, null, 0, 0), "owner@legacy.es");

        // 1. First observation at t0: sends pause notice FIRST (does NOT jump to contract ended!)
        assertThat(job.run(deadline())).isEqualTo(1);
        verify(mail, times(1)).sendOrdersPausedNotice("owner@legacy.es", "legacy", pausedSince, expectedContractEndsAt);
        verifyNoMoreInteractions(mail);
        BillingAccount afterFirstRun = billing.find("legacy").orElseThrow();
        assertThat(afterFirstRun.pausedSince()).isEqualTo(pausedSince);
        assertThat(afterFirstRun.pauseNotifiedAt()).isEqualTo(t0);
        assertThat(afterFirstRun.warningNotifiedAt()).isNull();
        assertThat(afterFirstRun.contractEndedAt()).isNull();
        assertThat(afterFirstRun.closedAt()).isNull();

        // Running again before t0 + 23d does nothing
        currentInstant.set(t0.plus(10, ChronoUnit.DAYS));
        assertThat(job.run(deadline())).isZero();
        verifyNoMoreInteractions(mail);

        // 2. 23 days after first observation (t0 + 23d): sends 7-day warning
        currentInstant.set(t0.plus(23, ChronoUnit.DAYS));
        assertThat(job.run(deadline())).isEqualTo(1);
        verify(mail, times(1)).sendContractEndingWarning("owner@legacy.es", "legacy", expectedContractEndsAt);
        assertThat(billing.find("legacy").orElseThrow().warningNotifiedAt()).isEqualTo(t0.plus(23, ChronoUnit.DAYS));
        assertThat(billing.find("legacy").orElseThrow().contractEndedAt()).isNull();

        // 3. 30 days after first observation (t0 + 30d): ends the contract
        currentInstant.set(expectedContractEndsAt);
        Instant expectedDeletionAt = expectedContractEndsAt.plus(30, ChronoUnit.DAYS); // t0 + 60d
        assertThat(job.run(deadline())).isEqualTo(1);
        verify(mail, times(1)).sendContractEndedNotice("owner@legacy.es", "legacy", expectedContractEndsAt, expectedDeletionAt);
        assertThat(billing.find("legacy").orElseThrow().contractEndedAt()).isEqualTo(expectedContractEndsAt);
        assertThat(billing.find("legacy").orElseThrow().closedAt()).isNull();

        // 4. 30 days after contract end (t0 + 60d): deletes non-fiscal data and closes the location
        currentInstant.set(expectedDeletionAt);
        assertThat(job.run(deadline())).isEqualTo(1);
        verify(backups, times(1)).cleanTenantData("legacy");
        assertThat(billing.find("legacy").orElseThrow().closedAt()).isEqualTo(expectedDeletionAt);
    }

    @Test
    void transitionsProceedAtSamePaceWithoutEmailAddressAndSmtpErrorDoesNotAbortTransition() {
        Instant t0 = currentInstant.get();
        Instant trialStartedAt = t0.minus(30, ChronoUnit.DAYS);
        Instant expectedContractEndsAt = t0.plus(30, ChronoUnit.DAYS);
        Instant expectedDeletionAt = expectedContractEndsAt.plus(30, ChronoUnit.DAYS);

        // Location 1: no email address anywhere
        billing.put(new BillingAccount("noemail", "owner_username_only", "key-ne", trialStartedAt,
                "LOCAL_TRIAL", null, null, 0, 0, false, null, null, 0, 0), null);

        // Location 2: has email, but SMTP throws on pause notice
        billing.put(new BillingAccount("smtpfail", "owner@smtp.es", "key-smtp", trialStartedAt,
                "LOCAL_TRIAL", null, null, 0, 0, false, null, null, 0, 0), "owner@smtp.es");
        doThrow(new RuntimeException("SMTP timeout"))
                .when(mail).sendOrdersPausedNotice(eq("owner@smtp.es"), eq("smtpfail"), any(), any());

        // Day 0: both locations record pauseNotifiedAt = t0 even when email is missing or SMTP throws
        assertThat(job.run(deadline())).isEqualTo(2);
        assertThat(billing.find("noemail").orElseThrow().pauseNotifiedAt()).isEqualTo(t0);
        assertThat(billing.find("smtpfail").orElseThrow().pauseNotifiedAt()).isEqualTo(t0);

        // Day 23: both record warningNotifiedAt
        currentInstant.set(t0.plus(23, ChronoUnit.DAYS));
        assertThat(job.run(deadline())).isEqualTo(2);
        assertThat(billing.find("noemail").orElseThrow().warningNotifiedAt()).isEqualTo(t0.plus(23, ChronoUnit.DAYS));
        assertThat(billing.find("smtpfail").orElseThrow().warningNotifiedAt()).isEqualTo(t0.plus(23, ChronoUnit.DAYS));

        // Day 30: both record contractEndedAt
        currentInstant.set(expectedContractEndsAt);
        assertThat(job.run(deadline())).isEqualTo(2);
        assertThat(billing.find("noemail").orElseThrow().contractEndedAt()).isEqualTo(expectedContractEndsAt);
        assertThat(billing.find("smtpfail").orElseThrow().contractEndedAt()).isEqualTo(expectedContractEndsAt);

        // Day 60: both close on schedule
        currentInstant.set(expectedDeletionAt);
        assertThat(job.run(deadline())).isEqualTo(2);
        assertThat(billing.find("noemail").orElseThrow().closedAt()).isEqualTo(expectedDeletionAt);
        assertThat(billing.find("smtpfail").orElseThrow().closedAt()).isEqualTo(expectedDeletionAt);
        verify(mail, never()).sendOrdersPausedNotice(anyString(), eq("noemail"), any(), any());
        verify(mail, never()).sendContractEndingWarning(anyString(), eq("noemail"), any());
        verify(mail, never()).sendContractEndedNotice(anyString(), eq("noemail"), any(), any());
    }

    @Test
    void whenDeletionSwitchIsOffNoDataIsDeletedAndContractEndedStateIsKept() {
        ContractLifecycleJob jobDeletionDisabled = new ContractLifecycleJob(
                billing, locations, backups, mail, routingResolver,
                new PlatformEnvironment("production"), false, false, clock);

        Instant t0 = currentInstant.get();
        Instant endedAt = t0.minus(35, ChronoUnit.DAYS); // contract ended 35 days ago (> 30d export window)
        billing.put(new BillingAccount("switchloc", "owner@switch.es", "key-sw",
                t0.minus(95, ChronoUnit.DAYS), "LOCAL_TRIAL", null, null, 0, 0, false, null, null, 0, 0,
                null, false, t0.minus(65, ChronoUnit.DAYS), t0.minus(65, ChronoUnit.DAYS),
                t0.minus(42, ChronoUnit.DAYS), endedAt, null, null), "owner@switch.es");

        // With deletionEnabled=false, step 5 only logs and does not delete or close
        assertThat(jobDeletionDisabled.run(deadline())).isZero();
        verify(backups, never()).cleanTenantData(anyString());
        assertThat(billing.closedLocations).isEmpty();
        BillingAccount stillEnded = billing.find("switchloc").orElseThrow();
        assertThat(stillEnded.isContractEnded()).isTrue();
        assertThat(stillEnded.isClosed()).isFalse();
        assertThat(stillEnded.closingStartedAt()).isNull();
        assertThat(stillEnded.displayStatus()).isEqualTo("Contract ended");

        // Owner can still pay and reinstate while deletion is disabled
        long renewedUntil = t0.plus(30, ChronoUnit.DAYS).getEpochSecond();
        billing.updateLocked("switchloc", a -> a.subscribed("cus_sw", "sub_sw", "active", renewedUntil, 0, false));
        assertThat(jobDeletionDisabled.run(deadline())).isEqualTo(1);
        assertThat(billing.find("switchloc").orElseThrow().isContractEnded()).isFalse();
    }

    @Test
    void twoRunsAtOnceAndCrashBetweenCleanAndClosedAtCloseLocationExactlyOnceOutsideRowLock() throws Exception {
        Instant t0 = currentInstant.get();
        Instant endedAt = t0.minus(30, ChronoUnit.DAYS);
        billing.put(new BillingAccount("concloc", "owner@conc.es", "key-c",
                t0.minus(90, ChronoUnit.DAYS), "LOCAL_TRIAL", null, null, 0, 0, false, null, null, 0, 0,
                null, false, t0.minus(60, ChronoUnit.DAYS), t0.minus(60, ChronoUnit.DAYS),
                t0.minus(37, ChronoUnit.DAYS), endedAt, null, null), "owner@conc.es");

        CountDownLatch insideClean = new CountDownLatch(1);
        CountDownLatch proceedClean = new CountDownLatch(1);
        AtomicInteger cleanCount = new AtomicInteger(0);

        doAnswer(inv -> {
            // Must never hold the BillingPort row lock while running cleanTenantData
            assertThat(billing.isLockedByCurrentThread()).isFalse();
            cleanCount.incrementAndGet();
            insideClean.countDown();
            assertThat(proceedClean.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(backups).cleanTenantData("concloc");

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> run1 = pool.submit(() -> job.run(deadline()));
            assertThat(insideClean.await(5, TimeUnit.SECONDS)).isTrue();

            // While run1 is mid-clean (outside the row lock, with closing_started_at leased), run2 executes concurrently
            Future<Integer> run2 = pool.submit(() -> job.run(deadline()));
            assertThat(run2.get(5, TimeUnit.SECONDS)).isZero();

            proceedClean.countDown();
            assertThat(run1.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }

        assertThat(cleanCount.get()).isEqualTo(1);
        assertThat(billing.closedLocations.get("concloc")).isEqualTo(t0);
        assertThat(billing.find("concloc").orElseThrow().closedAt()).isEqualTo(t0);

        // Part 2: simulate a crash after closing_started_at was set (and after clean ran), before closed_at was set
        Instant crashStart = t0.minus(ContractLifecycleJob.CLOSING_LEASE).minusSeconds(10);
        billing.put(new BillingAccount("crashedloc", "owner@crash.es", "key-cr",
                t0.minus(90, ChronoUnit.DAYS), "LOCAL_TRIAL", null, null, 0, 0, false, null, null, 0, 0,
                null, false, t0.minus(60, ChronoUnit.DAYS), t0.minus(60, ChronoUnit.DAYS),
                t0.minus(37, ChronoUnit.DAYS), endedAt, crashStart, null), "owner@crash.es");

        assertThat(job.run(deadline())).isEqualTo(1);
        verify(backups, times(1)).cleanTenantData("crashedloc");
        assertThat(billing.find("crashedloc").orElseThrow().closedAt()).isEqualTo(t0);
    }

    @Test
    void regularisingBeforeDay30ClearsPauseClockAndPayingBetweenDay30AndDay60ReinstatesContract() {
        Instant trialStartedAt = Instant.parse("2026-09-01T10:00:00Z");
        billing.put(new BillingAccount("cafe", "owner@cafe.es", "key-2", trialStartedAt,
                "LOCAL_TRIAL", null, null, 0, 0, false, null, null, 0, 0), "owner@cafe.es");

        // Pause at day 0
        assertThat(job.run(deadline())).isEqualTo(1);
        assertThat(billing.find("cafe").orElseThrow().pausedSince()).isNotNull();

        // Owner activates subscription on day 10 (before day 30)
        currentInstant.set(currentInstant.get().plus(10, ChronoUnit.DAYS));
        long activeUntil = currentInstant.get().plus(30, ChronoUnit.DAYS).getEpochSecond();
        billing.updateLocked("cafe", a -> a.subscribed("cus_1", "sub_1", "active", activeUntil, 0, false));

        assertThat(job.run(deadline())).isEqualTo(1);
        BillingAccount regularised = billing.find("cafe").orElseThrow();
        assertThat(regularised.pausedSince()).isNull();
        assertThat(regularised.pauseNotifiedAt()).isNull();
        assertThat(regularised.warningNotifiedAt()).isNull();
        assertThat(regularised.contractEndedAt()).isNull();
        assertThat(regularised.canTakeOrders(currentInstant.get(), true)).isTrue();

        // Later, subscription expires at activeUntil: first run observes the pause, 30 days later contract ends
        Instant subExpiredAt = Instant.ofEpochSecond(activeUntil);
        currentInstant.set(subExpiredAt);
        assertThat(job.run(deadline())).isEqualTo(1);
        assertThat(billing.find("cafe").orElseThrow().pauseNotifiedAt()).isEqualTo(subExpiredAt);

        currentInstant.set(subExpiredAt.plus(30, ChronoUnit.DAYS));
        assertThat(job.run(deadline())).isEqualTo(1);
        BillingAccount ended = billing.find("cafe").orElseThrow();
        assertThat(ended.pausedSince()).isEqualTo(subExpiredAt);
        assertThat(ended.contractEndedAt()).isEqualTo(currentInstant.get());
        assertThat(ended.canTakeOrders(currentInstant.get(), true)).isFalse();

        // Owner pays 10 days after contract ended (before 30-day data deletion) -> contract is reinstated!
        currentInstant.set(currentInstant.get().plus(10, ChronoUnit.DAYS));
        long renewedUntil = currentInstant.get().plus(30, ChronoUnit.DAYS).getEpochSecond();
        billing.updateLocked("cafe", a -> a.subscribed("cus_1", "sub_1", "active", renewedUntil, 0, false));

        assertThat(job.run(deadline())).isEqualTo(1);
        BillingAccount reinstated = billing.find("cafe").orElseThrow();
        assertThat(reinstated.contractEndedAt()).isNull();
        assertThat(reinstated.pausedSince()).isNull();
        assertThat(reinstated.pauseNotifiedAt()).isNull();
        assertThat(reinstated.warningNotifiedAt()).isNull();
        assertThat(reinstated.closedAt()).isNull();
        assertThat(reinstated.canTakeOrders(currentInstant.get(), true)).isTrue();
    }

    @Test
    void skipsFreePlanLocationsAndDoesNothingInSandbox() {
        Instant trialStartedAt = Instant.parse("2026-01-01T00:00:00Z");
        billing.put(new BillingAccount("freeloc", "owner@free.es", "key-free", trialStartedAt,
                "FREE", null, null, 0, 0, false, null, null, 0, 0, null, false), "owner@free.es");

        // Free-plan location in production is excluded from contract termination
        assertThat(job.run(deadline())).isZero();
        verifyNoInteractions(mail, backups);

        // In sandbox environment, even an expired trial is never touched by ContractLifecycleJob
        billing.put(new BillingAccount("sandboxloc", "owner@sbx.es", "key-sbx", trialStartedAt,
                "LOCAL_TRIAL", null, null, 0, 0, false, null, null, 0, 0), "owner@sbx.es");
        ContractLifecycleJob sandboxJob = new ContractLifecycleJob(billing, locations, backups, mail, routingResolver,
                new PlatformEnvironment("sandbox"), false, true, clock);
        assertThat(sandboxJob.run(deadline())).isZero();
        verifyNoInteractions(mail, backups);
    }

    private Instant deadline() {
        return currentInstant.get().plus(Duration.ofMinutes(4));
    }

    private static final class InMemoryBillingPort implements BillingPort {
        private final Map<String, BillingAccount> accounts = new LinkedHashMap<>();
        private final Map<String, String> emails = new LinkedHashMap<>();
        private final Map<String, Instant> closedLocations = new LinkedHashMap<>();
        private final ThreadLocal<Boolean> lockedByCurrentThread = ThreadLocal.withInitial(() -> false);

        boolean isLockedByCurrentThread() {
            return lockedByCurrentThread.get();
        }

        synchronized void put(BillingAccount account, String email) {
            accounts.put(account.tenantId(), account);
            if (email != null) {
                emails.put(account.tenantId(), email);
            }
        }

        @Override public synchronized Optional<BillingAccount> find(String tenant) {
            return Optional.ofNullable(accounts.get(tenant));
        }

        @Override public synchronized List<BillingAccount> findAll() {
            return List.copyOf(accounts.values());
        }

        @Override public synchronized BillingAccount updateLocked(String tenant, UnaryOperator<BillingAccount> update) {
            lockedByCurrentThread.set(true);
            try {
                BillingAccount before = accounts.get(tenant);
                if (before == null) throw new IllegalArgumentException("Missing billing account");
                BillingAccount after = update.apply(before);
                accounts.put(tenant, after);
                return after;
            } finally {
                lockedByCurrentThread.set(false);
            }
        }

        @Override public Optional<String> resolveOwnerEmail(String tenantId, String ownerUsername) {
            assertThat(lockedByCurrentThread.get()).isFalse();
            synchronized (this) {
                return Optional.ofNullable(emails.get(tenantId));
            }
        }

        @Override public void closeLocationData(String tenantId, Instant now) {
            assertThat(lockedByCurrentThread.get()).isFalse();
            synchronized (this) {
                closedLocations.put(tenantId, now);
            }
        }

        @Override public void requestGroupQuote(String owner, String email, int locations) {}
        @Override public PlatformBillingSettings getPlatformSettings() { return PlatformBillingSettings.defaultSettings(); }
        @Override public void updatePlatformSettings(PlatformBillingSettings settings) {}
        @Override public void updateTenantPlan(String tenantId, String plan) {}
        @Override public void extendTenantTrial(String tenantId, int extraDays) {}
    }
}
