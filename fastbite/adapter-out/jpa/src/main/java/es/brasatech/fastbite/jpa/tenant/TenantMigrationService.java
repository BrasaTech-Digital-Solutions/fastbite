package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.TenantRegistrationRules;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Separate history per schema. PUBLIC runs only the platform migrations; each tenant schema runs only the
 * restaurant migrations. Versions share one sequence across both folders.
 */
@Component
public class TenantMigrationService {
    /**
     * Restaurant versions that PUBLIC ran before V22 stopped restaurant migrations there. Flyway reports them as
     * applied but missing; only these may be.
     */
    static final Set<String> RETIRED_PUBLIC_VERSIONS =
            Set.of("1", "3", "8", "9", "10", "11", "12", "13", "14", "17", "19", "20", "21");

    private final DataSource dataSource;

    public TenantMigrationService(DataSource dataSource) { this.dataSource = dataSource; }

    public void migrateAll(boolean adoptExisting) {
        for (String schema : schemas()) {
            var flyway = flyway(schema, adoptExisting);
            if (isPublic(schema)) checkOnlyRetiredVersionsMissing(flyway);
            flyway.migrate();
        }
    }

    public void validateAll() {
        for (String schema : schemas()) {
            var flyway = flyway(schema, false);
            flyway.validate();
            if (isPublic(schema)) checkOnlyRetiredVersionsMissing(flyway);
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

    /** The local demo restaurant: its own schema like any restaurant, although its name is reserved. */
    public void provisionDemo() {
        synchronized (PROVISION_LOCK) {
            flyway(databaseCase("tenant_kebab"), false).migrate();
        }
    }

    private Flyway flyway(String schema, boolean adoptExisting) {
        if (!schema.equalsIgnoreCase("public") && !schema.toLowerCase(Locale.ROOT).matches("tenant_[a-z0-9_]{1,56}")) {
            throw new IllegalArgumentException("Invalid migration schema");
        }
        boolean platform = isPublic(schema);
        String[] locations = platform
                ? new String[]{"classpath:db/migration/platform"}
                : new String[]{"classpath:db/migration/restaurant"};
        return Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema)
                .locations(locations).resourceProvider(new MigrationResourceProvider(locations))
                // Java migrations are registered explicitly; avoid Flyway's native-incompatible scanner.
                .javaMigrationClassProvider(java.util.List::of)
                .javaMigrations(platform
                        ? new JavaMigration[]{new V22__Platform_users_and_drop_restaurant_tables()}
                        : new JavaMigration[]{new V3__LegacyTranslations()})
                .baselineOnMigrate(adoptExisting).baselineVersion("0")
                .cleanDisabled(true).failOnMissingLocations(true)
                // PUBLIC keeps the history of the restaurant versions it ran before V22;
                // checkOnlyRetiredVersionsMissing allows exactly those.
                .ignoreMigrationPatterns(platform ? new String[]{"*:missing"} : new String[0]).load();
    }

    private static boolean isPublic(String schema) { return schema.equalsIgnoreCase("public"); }

    private static void checkOnlyRetiredVersionsMissing(Flyway flyway) {
        for (MigrationInfo migration : flyway.info().all()) {
            if (migration.getState().isApplied() && !migration.getState().isResolved() && migration.getVersion() != null
                    && !RETIRED_PUBLIC_VERSIONS.contains(migration.getVersion().getVersion())) {
                throw new IllegalStateException("Migration " + migration.getVersion()
                        + " is recorded in the public schema but no longer exists. Restore it before starting.");
            }
        }
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
