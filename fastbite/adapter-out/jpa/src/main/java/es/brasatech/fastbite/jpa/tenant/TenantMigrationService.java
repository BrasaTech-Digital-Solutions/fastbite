package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.TenantRegistrationRules;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Separate history per schema; platform migrations run only in PUBLIC. */
@Component
public class TenantMigrationService {
    private final DataSource dataSource;

    public TenantMigrationService(DataSource dataSource) { this.dataSource = dataSource; }

    public void migrateAll(boolean adoptExisting) {
        for (String schema : schemas()) flyway(schema, adoptExisting).migrate();
    }

    public void validateAll() {
        for (String schema : schemas()) {
            var flyway = flyway(schema, false);
            flyway.validate();
            if (flyway.info().pending().length != 0) {
                throw new IllegalStateException("Database migrations are pending for " + schema + ". Run the migration job first.");
            }
        }
    }

    private static final Object PROVISION_LOCK = new Object();

    public void provision(String tenantId) {
        String tenant = TenantRegistrationRules.tenantId(tenantId);
        synchronized (PROVISION_LOCK) {
            flyway(databaseCase("tenant_" + tenant), false).migrate();
        }
    }

    private Flyway flyway(String schema, boolean adoptExisting) {
        if (!schema.equalsIgnoreCase("public") && !schema.toLowerCase(Locale.ROOT).matches("tenant_[a-z0-9_]{1,56}")) {
            throw new IllegalArgumentException("Invalid migration schema");
        }
        String[] locations = schema.equalsIgnoreCase("public")
                ? new String[]{"classpath:db/migration/restaurant", "classpath:db/migration/platform"}
                : new String[]{"classpath:db/migration/restaurant"};
        return Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema)
                .locations(locations).resourceProvider(new MigrationResourceProvider(locations))
                // Java migrations are registered explicitly; avoid Flyway's native-incompatible scanner.
                .javaMigrationClassProvider(java.util.List::of)
                .javaMigrations(new V3__LegacyTranslations())
                .baselineOnMigrate(adoptExisting).baselineVersion("0")
                .cleanDisabled(true).failOnMissingLocations(true)
                .ignoreMigrationPatterns(new String[0]).load();
    }

    private volatile String databaseProduct;

    private String databaseCase(String schema) {
        String product = databaseProduct;
        if (product == null) {
            synchronized (this) {
                product = databaseProduct;
                if (product == null) {
                    try (var connection = dataSource.getConnection()) {
                        product = connection.getMetaData().getDatabaseProductName();
                        databaseProduct = product;
                    } catch (SQLException e) {
                        throw new IllegalStateException("Cannot inspect migration database", e);
                    }
                }
            }
        }
        if ("H2".equals(product)) return schema.toUpperCase(Locale.ROOT);
        if ("PostgreSQL".equals(product)) return schema.toLowerCase(Locale.ROOT);
        throw new IllegalStateException("Migrations support H2 and PostgreSQL only");
    }

    private List<String> schemas() {
        List<String> result = new ArrayList<>();
        result.add(databaseCase("public"));
        try (var connection = dataSource.getConnection(); var schemas = connection.getMetaData().getSchemas()) {
            while (schemas.next()) {
                String name = schemas.getString("TABLE_SCHEM");
                if (name.toLowerCase(Locale.ROOT).startsWith("tenant_")) result.add(name);
            }
        } catch (SQLException e) { throw new IllegalStateException("Cannot discover restaurant schemas"); }
        result.subList(1, result.size()).sort(String::compareTo);
        return result;
    }
}
