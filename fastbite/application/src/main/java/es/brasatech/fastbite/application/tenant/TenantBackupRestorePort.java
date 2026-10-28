package es.brasatech.fastbite.application.tenant;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public interface TenantBackupRestorePort {
    void exportBackup(String tenantId, OutputStream outputStream);
    void exportProductionBundle(String tenantId, OutputStream outputStream);
    RestoreResult importRestore(String tenantId, InputStream inputStream);
    RestoreResult importProductionBundle(String tenantId, InputStream inputStream);
    RestoreResult restorePromotionSnapshot(String tenantId, InputStream inputStream);
    RestoreResult importDemoTemplate(String tenantId, InputStream inputStream);
    RestoreResult importDemoTemplate(String tenantId, String templateId);
    void cleanTenantData(String tenantId);
    BackupPreview previewBackup(String tenantId, InputStream inputStream);
    List<DemoTemplateDescriptor> getAvailableTemplates(Locale locale);

    record RestoreResult(boolean fiscalProfileLocked) {
        public static final RestoreResult UNLOCKED = new RestoreResult(false);
        public static final RestoreResult LOCKED = new RestoreResult(true);

        public static RestoreResult of(boolean locked) {
            return locked ? LOCKED : UNLOCKED;
        }
    }

    record BackupPreview(int products, int orders, int accounts) {}

    /**
     * A production bundle exported by another version of FastBite. The sandbox and production
     * always run the same version, except for the few minutes of a release, so the owner only has
     * to export it again.
     */
    class VersionMismatch extends IllegalArgumentException {
        public VersionMismatch(String bundleVersion, String runningVersion) {
            super("This bundle was exported from FastBite " + (bundleVersion == null ? "(an older version)" : bundleVersion)
                    + ", but this location runs FastBite " + runningVersion
                    + ". Export it again from the sandbox once both run the same version, and import the new file.");
        }
    }

    record DemoTemplateDescriptor(
            String id,
            String name,
            String description,
            String icon,
            int productsCount,
            int categoriesCount,
            List<String> supportedLanguages
    ) {}
}
