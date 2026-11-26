package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.platform.PlatformAdmins;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.tenant.NifTrialPort;
import es.brasatech.fastbite.application.tenant.SubscriptionGateway;
import es.brasatech.fastbite.application.tenant.SubscriptionService;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NifTrialServiceAndMigrationTest {

    private static JdbcDataSource freshDb(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + "_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    private static void migratePlatform(JdbcDataSource ds, String target) {
        String[] locations = {"classpath:db/migration/platform"};
        var config = Flyway.configure()
                .dataSource(ds)
                .defaultSchema("PUBLIC")
                .schemas("PUBLIC")
                .locations(locations)
                .resourceProvider(new MigrationResourceProvider(locations))
                .javaMigrationClassProvider(java.util.List::of)
                .javaMigrations(new V22__Platform_users_and_drop_restaurant_tables());
        if (target != null) {
            config.target(target);
        }
        config.load().migrate();
    }

    @Test
    void v40BackfillsLatestTrialStartPerNif() throws Exception {
        JdbcDataSource ds = freshDb("v40_backfill");
        migratePlatform(ds, "39");

        Instant t1 = Instant.parse("2026-10-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-15T10:00:00Z");
        Instant tUnopened = Instant.parse("2026-11-01T10:00:00Z");

        try (var c = ds.getConnection()) {
            try (var ps = c.prepareStatement(
                    "INSERT INTO public.tenant_billing (tenant_id, billing_key, trial_started_at, status) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, "loc1"); ps.setString(2, UUID.randomUUID().toString()); ps.setTimestamp(3, Timestamp.from(t1)); ps.setString(4, "LOCAL_TRIAL"); ps.executeUpdate();
                ps.setString(1, "loc2"); ps.setString(2, UUID.randomUUID().toString()); ps.setTimestamp(3, Timestamp.from(t2)); ps.setString(4, "LOCAL_TRIAL"); ps.executeUpdate();
                ps.setString(1, "loc_unopened"); ps.setString(2, UUID.randomUUID().toString()); ps.setTimestamp(3, Timestamp.from(tUnopened)); ps.setString(4, "LOCAL_TRIAL"); ps.executeUpdate();
            }
            try (var ps = c.prepareStatement(
                    "INSERT INTO public.owner_onboarding (tenant_id, opened) VALUES (?, ?)")) {
                ps.setString(1, "loc1"); ps.setBoolean(2, true); ps.executeUpdate();
                ps.setString(1, "loc2"); ps.setBoolean(2, true); ps.executeUpdate();
                ps.setString(1, "loc_unopened"); ps.setBoolean(2, false); ps.executeUpdate();
            }
            try (var ps = c.prepareStatement(
                    "INSERT INTO public.fiscal_issuers (tax_id, tenant_id, first_seen_at) VALUES (?, ?, ?)")) {
                ps.setString(1, "B12345674"); ps.setString(2, "loc1"); ps.setTimestamp(3, Timestamp.from(t1)); ps.executeUpdate();
                ps.setString(1, "B12345674"); ps.setString(2, "loc2"); ps.setTimestamp(3, Timestamp.from(t2)); ps.executeUpdate();
                ps.setString(1, "B12345674"); ps.setString(2, "loc_unopened"); ps.setTimestamp(3, Timestamp.from(tUnopened)); ps.executeUpdate();
            }
        }

        migratePlatform(ds, null);

        var adapter = new NifTrialJdbcAdapter(ds);
        var trial = adapter.findByNif("b12345674");
        assertThat(trial).isPresent();
        // MAX(trial_started_at) wins so admin-extended trials do not lose granted days; unopened location is excluded.
        assertThat(trial.get().trialStartedAt()).isEqualTo(t2);
        assertThat(trial.get().trialEndsAt()).isEqualTo(t2.plus(30, ChronoUnit.DAYS));
    }

    @Test
    void nifTrialSurvivesDeleteAndSharesEndAcrossLocationsAndOwners(@TempDir Path uploads) throws Exception {
        JdbcDataSource ds = freshDb("nif_lifecycle");
        migratePlatform(ds, null);

        Instant day0 = Instant.parse("2026-10-01T10:00:00Z");
        Instant day10 = day0.plus(10, ChronoUnit.DAYS);
        Instant day40 = day0.plus(40, ChronoUnit.DAYS);

        var billingAdapter = new BillingJdbcAdapter(ds);
        var nifAdapter = new NifTrialJdbcAdapter(ds);
        var deletionAdapter = new TenantDeletionJdbcAdapter(ds, uploads.toString());
        var env = new PlatformEnvironment("production");
        var admins = new PlatformAdmins("admin@example.com");
        var gateway = mock(SubscriptionGateway.class);

        var subsDay0 = new SubscriptionService(billingAdapter, gateway, env, admins, nifAdapter, Clock.fixed(day0, ZoneOffset.UTC));
        var subsDay10 = new SubscriptionService(billingAdapter, gateway, env, admins, nifAdapter, Clock.fixed(day10, ZoneOffset.UTC));
        var subsDay40 = new SubscriptionService(billingAdapter, gateway, env, admins, nifAdapter, Clock.fixed(day40, ZoneOffset.UTC));

        try (var c = ds.getConnection()) {
            try (var ps = c.prepareStatement(
                    "INSERT INTO public.tenant_locations (id, owner_username, tenant_id, plan) VALUES (?, ?, ?, 'PRO')")) {
                ps.setString(1, "id1"); ps.setString(2, "owner1@example.com"); ps.setString(3, "shop1"); ps.executeUpdate();
                ps.setString(1, "id2"); ps.setString(2, "owner2@example.com"); ps.setString(3, "shop2"); ps.executeUpdate();
                ps.setString(1, "id3"); ps.setString(2, "owner2@example.com"); ps.setString(3, "othernifshop"); ps.executeUpdate();
            }
            BillingJdbcAdapter.createTrial(c, "shop1", "LOCAL_TRIAL", null);
            BillingJdbcAdapter.createTrial(c, "shop2", "LOCAL_TRIAL", null);
            BillingJdbcAdapter.createTrial(c, "othernifshop", "LOCAL_TRIAL", null);
            try (var ps = c.prepareStatement(
                    "INSERT INTO public.fiscal_issuers (tax_id, tenant_id, first_seen_at) VALUES (?, ?, ?)")) {
                ps.setString(1, "B12345674"); ps.setString(2, "shop1"); ps.setTimestamp(3, Timestamp.from(day0)); ps.executeUpdate();
                ps.setString(1, "B12345674"); ps.setString(2, "shop2"); ps.setTimestamp(3, Timestamp.from(day10)); ps.executeUpdate();
                ps.setString(1, "B99999999"); ps.setString(2, "othernifshop"); ps.setTimestamp(3, Timestamp.from(day10)); ps.executeUpdate();
            }
        }

        // First open with NIF B12345674 on day 0 -> 30-day trial
        subsDay0.startTrialIfNeeded("shop1", "B12345674", day0);
        assertThat(subsDay0.account("shop1", "owner1@example.com").trialEndsAt())
                .isEqualTo(day0.plus(30, ChronoUnit.DAYS));

        // Delete shop1 completely -> nif_trials survives!
        deletionAdapter.deleteTenantCompletely("shop1");
        assertThat(nifAdapter.findByNif("B12345674")).isPresent();

        // Another owner opens shop2 with the SAME NIF on day 10 -> gets only remaining 20 days!
        subsDay10.startTrialIfNeeded("shop2", "B12345674", day10);
        assertThat(subsDay10.account("shop2", "owner2@example.com").trialEndsAt())
                .isEqualTo(day0.plus(30, ChronoUnit.DAYS));
        assertThat(SubscriptionService.remainingTrialDays(
                subsDay10.account("shop2", "owner2@example.com").trialEndsAt(), day10)).isEqualTo(20L);

        // Different NIF gets a brand new 30-day trial on day 10!
        subsDay10.startTrialIfNeeded("othernifshop", "B99999999", day10);
        assertThat(subsDay10.account("othernifshop", "owner2@example.com").trialEndsAt())
                .isEqualTo(day10.plus(30, ChronoUnit.DAYS));

        // On day 40 (after day 30 end), shop2 has 0 days left and cannot take orders
        assertThat(subsDay40.canTakeOrders("shop2")).isFalse();

        // Admin extends shop2 trial by 15 days -> updates both tenant_billing and nif_trials!
        var extended = subsDay40.extendTenantTrial("shop2", "admin@example.com", 15);
        assertThat(extended.trialEndsAt()).isEqualTo(day0.plus(45, ChronoUnit.DAYS));
        assertThat(nifAdapter.findByNif("B12345674").orElseThrow().trialEndsAt())
                .isEqualTo(day0.plus(45, ChronoUnit.DAYS));
        assertThat(subsDay40.canTakeOrders("shop2")).isTrue();
    }

    @Test
    void pendingSandboxDeletionQueueAndOrdersInProgressCheck(@TempDir Path uploads) throws Exception {
        JdbcDataSource ds = freshDb("pending_and_orders");
        migratePlatform(ds, null);

        var queue = new PendingSandboxDeletionJdbcAdapter(ds);
        Instant now = Instant.parse("2026-11-26T12:00:00Z");
        queue.enqueue("bistro", "owner@example.com", "down", now, now.minusSeconds(5));
        assertThat(queue.due(now, 10)).hasSize(1);
        queue.reschedule("bistro", 2, now.plusSeconds(60), "still down", now);
        assertThat(queue.due(now, 10)).isEmpty();
        assertThat(queue.due(now.plusSeconds(61), 10)).hasSize(1);
        queue.markDone("bistro");
        assertThat(queue.due(now.plusSeconds(120), 10)).isEmpty();

        // Test hasOrdersInProgressToday on a tenant schema
        var deletion = new TenantDeletionJdbcAdapter(ds, uploads.toString());
        LocalDateTime todayStart = LocalDateTime.of(2026, 11, 26, 0, 0);
        LocalDateTime tomorrowStart = todayStart.plusDays(1);
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("CREATE SCHEMA tenant_orderst");
            st.execute("""
                    CREATE TABLE tenant_orderst.orders (
                        id VARCHAR(36) PRIMARY KEY,
                        created_at TIMESTAMP NOT NULL,
                        status SMALLINT NOT NULL,
                        payment_status SMALLINT NOT NULL,
                        order_channel SMALLINT NOT NULL,
                        service_type VARCHAR(20)
                    )
                    """);
            // Complete order today -> NOT in progress
            st.execute("INSERT INTO tenant_orderst.orders VALUES ('o1', TIMESTAMP '2026-11-26 10:00:00', 5, 1, 3, 'DINE_IN')");
            // Cancelled order today -> NOT in progress
            st.execute("INSERT INTO tenant_orderst.orders VALUES ('o2', TIMESTAMP '2026-11-26 10:05:00', 6, 0, 2, 'DINE_IN')");
            // Yesterday unpaid order -> NOT today
            st.execute("INSERT INTO tenant_orderst.orders VALUES ('o3', TIMESTAMP '2026-11-25 22:00:00', 1, 0, 2, 'DINE_IN')");
            // Abandoned unpaid online takeaway draft -> NOT in progress
            st.execute("INSERT INTO tenant_orderst.orders VALUES ('o4', TIMESTAMP '2026-11-26 11:00:00', 0, 0, 0, 'TAKEAWAY')");
        }
        assertThat(deletion.hasOrdersInProgressToday("orderst", todayStart, tomorrowStart)).isFalse();

        // Add an unpaid table order today -> IN PROGRESS!
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("INSERT INTO tenant_orderst.orders VALUES ('o5', TIMESTAMP '2026-11-26 12:00:00', 2, 0, 2, 'DINE_IN')");
        }
        assertThat(deletion.hasOrdersInProgressToday("orderst", todayStart, tomorrowStart)).isTrue();
    }
}
