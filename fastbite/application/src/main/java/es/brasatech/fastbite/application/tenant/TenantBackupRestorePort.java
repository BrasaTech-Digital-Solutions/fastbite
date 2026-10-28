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
    PromotionPreview previewPromotion(String tenantId, java.nio.file.Path bundlePath);
    List<DemoTemplateDescriptor> getAvailableTemplates(Locale locale);

    record PromotionPreview(
            String token,
            EntityDiff products,
            EntityDiff categories,
            EntityDiff customizations,
            EntityDiff tables,
            EntityDiff discounts,
            ThemeDiff theme,
            boolean settingsReplaced,
            java.util.List<ProductChange> productChanges,
            int pricesOverwritten,
            long expiresAt
    ) {
        public PromotionPreview withTokenAndExpiry(String newToken, long newExpiresAt) {
            return new PromotionPreview(newToken, products, categories, customizations, tables, discounts, theme, settingsReplaced, productChanges, pricesOverwritten, newExpiresAt);
        }

        public record EntityDiff(int added, int changed, int removed, int unchanged, java.util.List<String> removedNames) {
            public static EntityDiff empty() {
                return new EntityDiff(0, 0, 0, 0, java.util.List.of());
            }
        }

        public record ThemeDiff(String before, String after) {}

        public record ProductChange(
                String id,
                String name,
                java.math.BigDecimal priceBefore,
                java.math.BigDecimal priceAfter,
                boolean allergensChanged,
                boolean descriptionChanged,
                boolean customizationsChanged
        ) {}
    }

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
    class UnreadableBundle extends RuntimeException {
        public UnreadableBundle(String message) {
            super(message);
        }
        public UnreadableBundle(String message, Throwable cause) {
            super(message, cause);
        }
    }

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
