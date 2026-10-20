package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.domain.table.TableStatus;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.settings.RestaurantSettingsEntity;
import es.brasatech.fastbite.jpa.settings.RestaurantSettingsJpaRepository;
import es.brasatech.fastbite.jpa.table.TableEntity;
import es.brasatech.fastbite.jpa.table.TableJpaRepository;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = es.brasatech.fastbite.jpa.TestConfig.class)
class TrustedAndSupervisedTablesMigrationIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private TableJpaRepository tableRepository;
    @Autowired private RestaurantSettingsJpaRepository settingsRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void v21MigrationAddsTableQrTrustedToSettingsAndOverridesToDiningTables() {
        String tenantId = "v21test";
        String schema = "TENANT_" + tenantId.toUpperCase(Locale.ROOT);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + schema);

        // 1. Migrate tenant schema up to V20 (before V21)
        String[] locations = new String[]{"classpath:db/migration/restaurant"};
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema(schema)
                .schemas(schema)
                .locations(locations)
                .resourceProvider(new MigrationResourceProvider(locations))
                .javaMigrationClassProvider(List::of)
                .javaMigrations(new V3__LegacyTranslations())
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .failOnMissingLocations(true)
                .ignoreMigrationPatterns(new String[0])
                .target("20")
                .load()
                .migrate();

        // 2. Insert dining_table and restaurant_settings using raw JDBC under V20 schema
        jdbc.update("INSERT INTO " + schema + ".restaurant_settings (id, dine_in, takeaway, kds_yellow_minutes, kds_red_minutes, cash_float) VALUES (1, TRUE, TRUE, 0, 0, 0)");
        jdbc.update("INSERT INTO " + schema + ".dining_tables (id, name, seats, status, active) VALUES ('tbl-v20', 'Table V20', 4, 'AVAILABLE', TRUE)");

        // 3. Run migration V21
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema(schema)
                .schemas(schema)
                .locations(locations)
                .resourceProvider(new MigrationResourceProvider(locations))
                .javaMigrationClassProvider(List::of)
                .javaMigrations(new V3__LegacyTranslations())
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .failOnMissingLocations(true)
                .ignoreMigrationPatterns(new String[0])
                .target("21")
                .load()
                .migrate();

        // 4. Verify raw DB column defaults after V21
        Boolean defaultTableQrTrusted = jdbc.queryForObject(
                "SELECT table_qr_trusted FROM " + schema + ".restaurant_settings WHERE id = 1", Boolean.class);
        assertThat(defaultTableQrTrusted).isFalse();

        Boolean initialQrTrusted = jdbc.queryForObject(
                "SELECT qr_trusted FROM " + schema + ".dining_tables WHERE id = 'tbl-v20'", Boolean.class);
        assertThat(initialQrTrusted).isNull();

        String initialQrTrustedBy = jdbc.queryForObject(
                "SELECT qr_trusted_by FROM " + schema + ".dining_tables WHERE id = 'tbl-v20'", String.class);
        assertThat(initialQrTrustedBy).isNull();

        // 5. Verify JPA entity read and write on migrated schema
        TenantContext.setCurrentTenant(tenantId);
        var txTemplate = new TransactionTemplate(transactionManager);

        txTemplate.executeWithoutResult(status -> {
            RestaurantSettingsEntity settings = settingsRepository.findById(1).orElseThrow();
            assertThat(settings.isTableQrTrusted()).isFalse();

            TableEntity table = tableRepository.findById("tbl-v20").orElseThrow();
            assertThat(table.getQrTrusted()).isNull();
            assertThat(table.getQrTrustedBy()).isNull();
            assertThat(table.getQrTrustedAt()).isNull();

            // Mutate and save
            settings.setTableQrTrusted(true);
            settingsRepository.save(settings);

            table.setQrTrusted(true);
            table.setQrTrustedBy("admin-v21");
            table.setQrTrustedAt(LocalDateTime.now());
            tableRepository.save(table);

            entityManager.flush();
            entityManager.clear();
        });

        txTemplate.executeWithoutResult(status -> {
            RestaurantSettingsEntity updatedSettings = settingsRepository.findById(1).orElseThrow();
            assertThat(updatedSettings.isTableQrTrusted()).isTrue();

            TableEntity updatedTable = tableRepository.findById("tbl-v20").orElseThrow();
            assertThat(updatedTable.getQrTrusted()).isTrue();
            assertThat(updatedTable.getQrTrustedBy()).isEqualTo("admin-v21");
            assertThat(updatedTable.getQrTrustedAt()).isNotNull();
        });
    }
}
