package es.brasatech.fastbite.jpa.tenant;

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
        assertThat(versions("PUBLIC")).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19");
        assertThat(versions("TENANT_ALPHA")).containsExactly("1", "3", "8", "9", "10", "11", "12", "13", "14", "17", "19");
        assertThat(versions("TENANT_BETA")).containsExactly("1", "3", "8", "9", "10", "11", "12", "13", "14", "17", "19");
        assertThat(jdbc.queryForObject("SELECT price FROM tenant_alpha.products WHERE id = 'meal'", BigDecimal.class))
                .isEqualByComparingTo("12.50");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_beta.products", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'TENANT_ALPHA' AND table_name = 'TENANT_LIFECYCLE'", Integer.class)).isZero();
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
        assertThat(versions("TENANT_LEGACY")).containsExactly("0", "1", "3", "8", "9", "10", "11", "12", "13", "14", "17", "19");
        assertThat(jdbc.queryForObject("SELECT total FROM tenant_legacy.orders WHERE id = 'paid'", BigDecimal.class)).isEqualByComparingTo("19.95");
        assertThat(jdbc.queryForObject("SELECT translations FROM tenant_legacy.groups WHERE id = 'food'", String.class)).contains("Comida");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'TENANT_LEGACY' AND table_name = 'GROUP_TRANSLATIONS'", Integer.class)).isZero();
    }

    @Test void validationFailsForUnmigratedDatabaseWithoutCreatingTables() {
        assertThatThrownBy(migrations::validateAll).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'PUBLIC'", Integer.class)).isZero();
    }

    @Test void validationRefusesChangedAndUnknownMigrationHistory() {
        migrations.migrateAll(false);
        jdbc.update("UPDATE public.\"flyway_schema_history\" SET \"checksum\" = 0 WHERE \"version\" = '1'");
        assertThatThrownBy(migrations::validateAll).isInstanceOf(RuntimeException.class);
        jdbc.update("UPDATE public.\"flyway_schema_history\" SET \"version\" = '999' WHERE \"version\" = '1'");
        assertThatThrownBy(migrations::validateAll).isInstanceOf(RuntimeException.class);
    }

    @Test void failedMigrationHistoryStopsStartupAndIsNotAutomaticallyRepaired() {
        migrations.migrateAll(false);
        jdbc.update("UPDATE public.\"flyway_schema_history\" SET \"success\" = FALSE WHERE \"version\" = '3'");
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
        assertThat(versions("TENANT_CONCURRENT")).containsExactly("1", "3", "8", "9", "10", "11", "12", "13", "14", "17", "19");
    }

    private java.util.List<String> versions(String schema) {
        return jdbc.queryForList("SELECT \"version\" FROM " + schema + ".\"flyway_schema_history\" WHERE \"version\" IS NOT NULL ORDER BY \"installed_rank\"", String.class);
    }
}
