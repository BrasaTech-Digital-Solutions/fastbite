package es.brasatech.fastbite.jpa.tenant;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Production startup checks history; upgrades belong to the migration job. */
@Component
public class TenantSchemaInitializer implements InitializingBean {
    private final TenantMigrationService migrations;
    private final String mode;
    private final boolean adoptExisting;

    public TenantSchemaInitializer(TenantMigrationService migrations,
            @Value("${fastbite.migrations.mode:validate}") String mode,
            @Value("${fastbite.migrations.adopt-existing:false}") boolean adoptExisting) {
        this.migrations = migrations;
        this.mode = mode;
        this.adoptExisting = adoptExisting;
    }

    @Override public void afterPropertiesSet() {
        switch (mode) {
            case "migrate" -> migrations.migrateAll(adoptExisting);
            case "validate" -> migrations.validateAll();
            default -> throw new IllegalArgumentException("fastbite.migrations.mode must be migrate or validate");
        }
    }
}
