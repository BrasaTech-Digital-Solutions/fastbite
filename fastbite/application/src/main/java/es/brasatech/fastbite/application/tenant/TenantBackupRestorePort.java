package es.brasatech.fastbite.application.tenant;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public interface TenantBackupRestorePort {
    void exportBackup(String tenantId, OutputStream outputStream);
    void exportProductionBundle(String tenantId, OutputStream outputStream);
    void importRestore(String tenantId, InputStream inputStream);
    void importProductionBundle(String tenantId, InputStream inputStream);
    void importDemoTemplate(String tenantId, InputStream inputStream);
    void importDemoTemplate(String tenantId, String templateId);
    void cleanTenantData(String tenantId);
    BackupPreview previewBackup(String tenantId, InputStream inputStream);
    List<DemoTemplateDescriptor> getAvailableTemplates(Locale locale);

    record BackupPreview(int products, int orders, int accounts) {}

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
