package es.brasatech.fastbite;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import es.brasatech.fastbite.jpa.tenant.TenantMigrationService;

import java.util.Arrays;
import java.util.Set;

/** A short-lived JVM entry point: no HTTP server, JPA, demo seeding, mail or Stripe beans. */
final class MigrationJob {
    private MigrationJob() { }

    static int run(String[] args) {
        Application.configureDefaultTimeZone();
        Set<String> allowed = Set.of("--migration-job", "--adopt-existing", "--validate-only");
        if (Arrays.stream(args).anyMatch(arg -> !allowed.contains(arg))) {
            System.err.println("Unknown migration job option.");
            return 2;
        }
        String url = System.getenv("DB_URL");
        if (url == null || !(url.startsWith("jdbc:postgresql:") || url.startsWith("jdbc:h2:"))) {
            System.err.println("Set DB_URL explicitly to the H2 or PostgreSQL database to migrate.");
            return 2;
        }
        var options = Arrays.asList(args);
        if (options.contains("--validate-only") && options.contains("--adopt-existing")) return 2;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(System.getenv().getOrDefault("DB_USER", "sa"));
        config.setPassword(System.getenv().getOrDefault("DB_PASSWORD", ""));
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        config.setPoolName("migration-job");
        try (var source = new HikariDataSource(config)) {
            var migrations = new TenantMigrationService(source);
            if (options.contains("--validate-only")) migrations.validateAll();
            else migrations.migrateAll(options.contains("--adopt-existing"));
            System.out.println("Database migrations verified successfully.");
            return 0;
        } catch (RuntimeException e) {
            // Do not expose connection properties or SQL parameter values in the job error.
            System.err.println("Database migration failed (" + e.getClass().getSimpleName() + "). Review migration history before retrying.");
            return 1;
        }
    }
}
