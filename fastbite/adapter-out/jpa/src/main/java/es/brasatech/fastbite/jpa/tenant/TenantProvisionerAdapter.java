package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.TenantProvisionerPort;
import org.springframework.stereotype.Component;

@Component
public class TenantProvisionerAdapter implements TenantProvisionerPort {
    private final TenantMigrationService migrations;
    public TenantProvisionerAdapter(TenantMigrationService migrations) { this.migrations = migrations; }

    @Override public void provisionTenant(String tenantId) { migrations.provision(tenantId); }

    @Override public void provisionDemoTenant() { migrations.provisionDemo(); }
}
