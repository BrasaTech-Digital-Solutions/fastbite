package es.brasatech.fastbite.jpa.tenant;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class TenantMigrationServiceTest {
    private JdbcDataSource source;
    private JdbcTemplate jdbc;
    private TenantMigrationService migrations;

    @BeforeEach void database() {
        source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:migrations" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        source.setUser("sa");
        jdbc = new JdbcTemplate(source);
        migrations = new TenantMigrationService(source);
    }

    @Test void newSchemasHaveIndependentHistoriesAndRepeatedRunsDoNothing() {
        migrations.migrateAll(false);
        migrations.provision("alpha");
        migrations.provision("beta");
        jdbc.update("INSERT INTO tenant_alpha.products (id, name, price, active) VALUES ('meal', 'Meal', 12.50, TRUE)");
        migrations.migrateAll(false);
        migrations.provision("alpha");
        migrations.validateAll();
        assertThat(versions("PUBLIC")).containsExactly("2", "4", "5", "6", "7", "15", "16", "18", "22", "23", "24", "25", "27", "28", "29", "31", "32", "33", "34", "35", "36", "37", "38", "39");
        assertThat(tables("PUBLIC")).containsExactlyInAnyOrderElementsOf(PLATFORM_TABLES);
        assertThat(versions("TENANT_ALPHA")).containsExactly("1", "3", "8", "9", "10", "11", "12", "13", "14", "17", "19", "20", "21", "26", "30");
        assertThat(versions("TENANT_BETA")).containsExactly("1", "3", "8", "9", "10", "11", "12", "13", "14", "17", "19", "20", "21", "26", "30");
        assertThat(jdbc.queryForObject("SELECT price FROM tenant_alpha.products WHERE id = 'meal'", BigDecimal.class))
                .isEqualByComparingTo("12.50");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_beta.products", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'TENANT_ALPHA' AND table_name = 'TENANT_LIFECYCLE'", Integer.class)).isZero();
    }

    @Test void upgradeToGuestLimitsRemovesOnlyLegacyGuestQuotaRows() {
        String[] locations = {"classpath:db/migration/platform"};
        Flyway.configure().dataSource(source).defaultSchema("PUBLIC").schemas("PUBLIC")
                .locations(locations).resourceProvider(new MigrationResourceProvider(locations))
                .javaMigrationClassProvider(java.util.List::of)
                .javaMigrations(new V22__Platform_users_and_drop_restaurant_tables())
                .target("34").load().migrate();
        jdbc.update("INSERT INTO public.account_email_limits (id, subject_key, purpose, sent_at) VALUES ('guest-ticket', 'ticket', 'GUEST_INVOICE_TICKET', 1)");
        jdbc.update("INSERT INTO public.account_email_limits (id, subject_key, purpose, sent_at) VALUES ('guest-client', 'client', 'GUEST_INVOICE_CLIENT', 1)");
        jdbc.update("INSERT INTO public.account_email_limits (id, subject_key, purpose, sent_at) VALUES ('owner', 'owner', 'OWNER_SETUP', 1)");
        migrations.migrateAll(false);
        assertThat(jdbc.queryForList("SELECT purpose FROM public.account_email_limits", String.class)).containsExactly("OWNER_SETUP");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.signup_reservation_lock", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.guest_action_lock", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.guest_action_limits", Integer.class)).isZero();
        assertThat(versions("PUBLIC")).contains("35");
    }

    @Test void existingDatabaseNeedsExplicitAdoptionAndPreservesData() throws Exception {
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("pre-flyway-schema.sql"));
            connection.createStatement().execute("CREATE SCHEMA tenant_legacy");
            connection.setSchema("TENANT_LEGACY");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("pre-flyway-schema.sql"));
        }
        jdbc.update("INSERT INTO tenant_legacy.groups (id, name) VALUES ('food', 'Food')");
        jdbc.execute("CREATE TABLE tenant_legacy.group_translations (group_id VARCHAR(36), language VARCHAR(10), name VARCHAR(255), description VARCHAR(255))");
        jdbc.update("INSERT INTO tenant_legacy.group_translations VALUES ('food', 'es', 'Comida', 'Carta')");
        jdbc.update("INSERT INTO tenant_legacy.orders (id, order_number, created_at, updated_at, status, total, payment_status, order_channel, order_language) VALUES ('paid', 42, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 19.95, 1, 0, 'en')");
        assertThatThrownBy(() -> migrations.migrateAll(false)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT total FROM tenant_legacy.orders WHERE id = 'paid'", BigDecimal.class)).isEqualByComparingTo("19.95");
        migrations.migrateAll(true);
        migrations.validateAll();
        assertThat(versions("TENANT_LEGACY")).containsExactly("0", "1", "3", "8", "9", "10", "11", "12", "13", "14", "17", "19", "20", "21", "26", "30");
        assertThat(jdbc.queryForObject("SELECT total FROM tenant_legacy.orders WHERE id = 'paid'", BigDecimal.class)).isEqualByComparingTo("19.95");
        assertThat(jdbc.queryForObject("SELECT translations FROM tenant_legacy.groups WHERE id = 'food'", String.class)).contains("Comida");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'TENANT_LEGACY' AND table_name = 'GROUP_TRANSLATIONS'", Integer.class)).isZero();
    }

    @Test void ownerEmailsAreNormalizedAndUniqueAndStaffAreTenantScoped() {
        migrations.migrateAll(false);
        migrations.provision("alpha");
        migrations.provision("beta");
        jdbc.update("INSERT INTO public.users (id, username, password, active, email) VALUES ('one', 'one', 'hash', TRUE, 'owner@example.com')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO public.users (id, username, password, active, email) VALUES ('two', 'two', 'hash', TRUE, 'OWNER@example.com')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO public.users (id, username, password, active, email) VALUES ('two', 'two', 'hash', TRUE, 'owner@example.com')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO tenant_alpha.users (id, username, password, active, email) VALUES ('one', 'staff', 'hash', TRUE, 'staff@example.com')");
        jdbc.update("INSERT INTO tenant_beta.users (id, username, password, active, email) VALUES ('one', 'staff', 'hash', TRUE, 'staff@example.com')");
    }

    @Test void validationFailsForUnmigratedDatabaseWithoutCreatingTables() {
        assertThatThrownBy(migrations::validateAll).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'PUBLIC'", Integer.class)).isZero();
    }

    @Test void validationRefusesChangedAndUnknownMigrationHistory() {
        migrations.migrateAll(false);
        jdbc.update("UPDATE public.\"flyway_schema_history\" SET \"checksum\" = 0 WHERE \"version\" = '2'");
        assertThatThrownBy(migrations::validateAll).isInstanceOf(RuntimeException.class);
        jdbc.update("UPDATE public.\"flyway_schema_history\" SET \"version\" = '999' WHERE \"version\" = '2'");
        assertThatThrownBy(migrations::validateAll).isInstanceOf(RuntimeException.class);
    }

    @Test void failedMigrationHistoryStopsStartupAndIsNotAutomaticallyRepaired() {
        migrations.migrateAll(false);
        jdbc.update("UPDATE public.\"flyway_schema_history\" SET \"success\" = FALSE WHERE \"version\" = '4'");
        assertThatThrownBy(migrations::validateAll).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> migrations.migrateAll(false)).isInstanceOf(RuntimeException.class);
    }

    @Test void legacyKebabSchemaIsPreservedInsteadOfDropped() {
        jdbc.execute("CREATE SCHEMA tenant_kebab");
        jdbc.execute("CREATE TABLE tenant_kebab.keep_me (id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO tenant_kebab.keep_me VALUES (1)");
        migrations.migrateAll(true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_kebab.keep_me", Integer.class)).isEqualTo(1);
    }

    @Test void concurrentMigrationWorkersKeepOneHistoryEntryPerVersion() throws Exception {
        migrations.migrateAll(false);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> migrations.provision("concurrent"));
            var second = executor.submit(() -> new TenantMigrationService(source).provision("concurrent"));
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        assertThat(versions("TENANT_CONCURRENT")).containsExactly("1", "3", "8", "9", "10", "11", "12", "13", "14", "17", "19", "20", "21", "26", "30");
    }

    @Test void publicUpgradedFromBeforeV22KeepsOnlyPlatformTablesAndItsAccounts() {
        migratePublicAsBeforeV22();
        migrations.provision("alpha");
        jdbc.update("INSERT INTO tenant_alpha.products (id, name, price, active) VALUES ('meal', 'Meal', 12.50, TRUE)");
        jdbc.update("INSERT INTO public.users (id, username, password, active, tenant_id) VALUES ('owner', 'owner', 'hash', TRUE, 'alpha')");
        jdbc.update("INSERT INTO public.user_roles (user_id, role) VALUES ('owner', 'OWNER')");

        migrations.migrateAll(false);
        migrations.validateAll();

        assertThat(tables("PUBLIC")).containsExactlyInAnyOrderElementsOf(PLATFORM_TABLES);
        assertThat(versions("PUBLIC")).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14",
                "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "27", "28", "29", "31", "32", "33", "34", "35", "36", "37", "38", "39");
        assertThat(jdbc.queryForObject("SELECT role FROM public.user_roles WHERE user_id = 'owner'", String.class)).isEqualTo("OWNER");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_alpha.products", Integer.class)).isEqualTo(1);
        assertThat(versions("TENANT_ALPHA")).containsExactly("1", "3", "8", "9", "10", "11", "12", "13", "14", "17", "19", "20", "21", "26", "30");
    }

    @Test void freshAndUpgradedPublicDefineTheAccountsTheSameWay() {
        migrations.migrateAll(false);
        var fresh = accountColumns();
        database();
        migratePublicAsBeforeV22();
        migrations.migrateAll(false);
        assertThat(accountColumns()).isEqualTo(fresh);
    }

    @Test void restaurantDataInPublicStopsTheMigrationAndNothingIsDropped() {
        String[][] cases = {
                {"orders", "INSERT INTO public.orders (id, order_number, created_at, updated_at, status, total, payment_status, order_channel, order_language) VALUES ('stray', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 1.00, 0, 0, 'en')"},
                {"verifactu_chain", "UPDATE public.verifactu_chain SET records = 1 WHERE chain = 'REAL'"},
                {"order_counter", "UPDATE public.order_counter SET last_number = 7 WHERE id = 1"}};
        for (String[] stray : cases) {
            database();
            migratePublicAsBeforeV22();
            jdbc.update(stray[1]);
            assertThatThrownBy(() -> migrations.migrateAll(false))
                    .hasStackTraceContaining("PUBLIC." + stray[0] + ": it holds 1 row");
            assertThat(tables("PUBLIC")).contains("ORDERS", "VERIFACTU_CHAIN", "ORDER_COUNTER", "PRODUCTS");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.\"flyway_schema_history\" WHERE \"version\" = '22' AND \"success\"", Integer.class)).isZero();
        }
    }

    @Test void anUnknownMissingPlatformMigrationStopsStartup() {
        migrations.migrateAll(false);
        // A platform version this build no longer has, below the latest: Flyway itself would ignore it as missing
        jdbc.update("INSERT INTO public.\"flyway_schema_history\" SELECT \"installed_rank\" + 100, '19.5', \"description\", \"type\","
                + " \"script\", \"checksum\", \"installed_by\", \"installed_on\", \"execution_time\", \"success\""
                + " FROM public.\"flyway_schema_history\" WHERE \"version\" = '5'");
        assertThatThrownBy(migrations::validateAll).hasMessageContaining("Migration 19.5");
        assertThatThrownBy(() -> migrations.migrateAll(false)).hasMessageContaining("Migration 19.5");
    }

    @Test void theDemoRestaurantHasItsOwnSchema() {
        migrations.migrateAll(false);
        migrations.provisionDemo();
        assertThat(versions("TENANT_KEBAB")).containsExactly("1", "3", "8", "9", "10", "11", "12", "13", "14", "17", "19", "20", "21", "26", "30");
    }

    @Test void theV22MigrationRunsInOneTransaction() {
        assertThat(new V22__Platform_users_and_drop_restaurant_tables().canExecuteInTransaction()).isTrue();
    }

    private static final java.util.List<String> PLATFORM_TABLES = java.util.List.of("flyway_schema_history",
            "SPRING_SESSION", "SPRING_SESSION_ATTRIBUTES", "USERS", "USER_ROLES", "TENANT_LOCATIONS", "OWNER_SETUP_TOKENS",
            "TENANT_LIFECYCLE", "TENANT_BILLING", "GROUP_QUOTE_REQUESTS", "OWNER_ONBOARDING", "OWNER_APPEARANCE",
            "LOCATION_APPEARANCE", "SANDBOX_PROVISIONING", "SANDBOX_SIGN_IN", "FISCAL_ISSUERS", "LEGAL_ACCEPTANCES",
            "LOCATION_PROMOTIONS", "LOCATION_PROMOTION_LEASE", "PENDING_REGISTRATIONS", "ACCOUNT_TOKENS", "ACCOUNT_EMAIL_OUTBOX", "ACCOUNT_EMAIL_LIMITS", "SIGNUP_RESERVATION_LOCK", "GUEST_ACTION_LIMITS", "GUEST_ACTION_LOCK", "VERIFACTU_AUTHORIZATIONS", "DIGITAL_SIGN_WEBHOOK_EVENTS");

    /** PUBLIC as every release before V22 left it: platform and restaurant migrations together. */
    private void migratePublicAsBeforeV22() {
        String[] locations = {"classpath:db/migration/restaurant", "classpath:db/migration/platform"};
        Flyway.configure().dataSource(source).defaultSchema("PUBLIC").schemas("PUBLIC")
                .locations(locations).resourceProvider(new MigrationResourceProvider(locations))
                .javaMigrationClassProvider(java.util.List::of).javaMigrations(new V3__LegacyTranslations())
                .target("21").load().migrate();
    }

    private java.util.List<String> tables(String schema) {
        return jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = '" + schema + "'", String.class);
    }

    private java.util.List<String> accountColumns() {
        return jdbc.queryForList("SELECT table_name || '.' || column_name || ' ' || data_type || ' ' || COALESCE(character_maximum_length, 0)"
                + " || ' ' || is_nullable || ' ' || COALESCE(column_default, '') FROM information_schema.columns"
                + " WHERE table_schema = 'PUBLIC' AND table_name IN ('USERS', 'USER_ROLES') ORDER BY table_name, column_name", String.class);
    }

    private java.util.List<String> versions(String schema) {
        return jdbc.queryForList("SELECT \"version\" FROM " + schema + ".\"flyway_schema_history\" WHERE \"version\" IS NOT NULL ORDER BY \"installed_rank\"", String.class);
    }
}
