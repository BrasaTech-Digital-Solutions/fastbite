package es.brasatech.fastbite.config;

import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@org.springframework.context.annotation.Profile({"local", "demo"})
@Slf4j
@org.springframework.core.annotation.Order(1)
public class DemoDataInitializer implements CommandLineRunner {

    private final TenantBackupRestorePort tenantBackupRestorePort;
    private final es.brasatech.fastbite.application.tenant.TenantProvisionerPort tenantProvisioner;
    private final String uploadDirectory;
    private final jakarta.persistence.EntityManager entityManager;

    public DemoDataInitializer(TenantBackupRestorePort tenantBackupRestorePort,
                               es.brasatech.fastbite.application.tenant.TenantProvisionerPort tenantProvisioner,
                               @org.springframework.beans.factory.annotation.Value("${image.upload.directory}") String uploadDirectory,
                               jakarta.persistence.EntityManager entityManager) {
        this.tenantBackupRestorePort = tenantBackupRestorePort;
        this.tenantProvisioner = tenantProvisioner;
        this.uploadDirectory = uploadDirectory;
        this.entityManager = entityManager;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("Checking demo data initialization for default tenants...");
        // The demo restaurant has its own schema, like any restaurant
        tenantProvisioner.provisionDemoTenant();
        initializeTenantDemo("kebab");
    }

    private void initializeTenantDemo(String tenantId) {
        try {
            // Check if tenant has any data in the database
            es.brasatech.fastbite.domain.tenant.TenantContext.setCurrentTenant(tenantId);
            long count = 0;
            try {
                count = ((Number) entityManager.createQuery("SELECT count(g) FROM Group g").getSingleResult()).longValue();
            } catch (Exception ex) {
                log.warn("Failed to check groups count for tenant {}: {}", tenantId, ex.getMessage());
            } finally {
                es.brasatech.fastbite.domain.tenant.TenantContext.clear();
            }

            // Check if tenant upload directory is empty/missing
            Path tenantComboPath = Paths.get(uploadDirectory).resolve(tenantId).resolve("combo");
            if (count == 0 || !Files.exists(tenantComboPath)) {
                log.info("Provisioning demo data template for tenant: {} (count={}, media_exists={})", 
                         tenantId, count, Files.exists(tenantComboPath));
                try {
                    tenantBackupRestorePort.importDemoTemplate(tenantId, "kebab");
                    log.info("Successfully loaded default demo template for tenant: {}", tenantId);
                } catch (Exception e) {
                    log.warn("Default demo template 'kebab' could not be loaded at startup: {}", e.getMessage());
                }
            } else {
                log.info("Demo data already loaded for tenant: {}", tenantId);
            }
        } catch (Exception e) {
            log.error("Failed to auto-provision demo data for tenant: " + tenantId, e);
        }
    }
}
