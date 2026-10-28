package es.brasatech.fastbite.jpa.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort.PromotionPreview;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.TestConfig;
import es.brasatech.fastbite.jpa.product.ProductEntity;
import es.brasatech.fastbite.jpa.tenant.TenantBackupRestoreAdapter.TenantBackupData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TestConfig.class)
class PromotionPreviewDiffTest {

    @Autowired
    private TenantBackupRestorePort backupRestorePort;

    @Autowired
    private TenantProvisionerAdapter tenantProvisionerAdapter;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void previewPromotionComputesDiffAndPricesOverwrittenAndRefusesNullOrWrongVersion() throws Exception {
        String tenant = "pdiff" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        tenantProvisionerAdapter.provisionTenant(tenant);
        TenantContext.setCurrentTenant(tenant);

        var template = backupRestorePort.getAvailableTemplates(Locale.ENGLISH).getFirst();
        backupRestorePort.importDemoTemplate(tenant, template.id());

        ByteArrayOutputStream bundleOut = new ByteArrayOutputStream();
        backupRestorePort.exportProductionBundle(tenant, bundleOut);
        byte[] originalBundle = bundleOut.toByteArray();

        // Read data.json from bundle
        TenantBackupData data = null;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(originalBundle))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
                if ("data.json".equals(entry.getName())) {
                    data = objectMapper.readValue(in, TenantBackupData.class);
                    break;
                }
            }
        }
        assertThat(data).isNotNull();
        assertThat(data.getProducts()).hasSizeGreaterThanOrEqualTo(2);

        // Mutate data:
        // 1. Change price of product 0
        ProductEntity p0 = data.getProducts().get(0);
        p0.setPrice(BigDecimal.valueOf(99.99));

        // 2. Remove product 1 from bundle
        ProductEntity removedProd = data.getProducts().remove(1);

        // 3. Add brand new product
        ProductEntity newProd = new ProductEntity();
        newProd.setId("new-prod-" + UUID.randomUUID());
        newProd.setName("Brand New Dish");
        newProd.setPrice(BigDecimal.valueOf(19.99));
        newProd.setActive(true);
        data.getProducts().add(newProd);

        // Pack into temp zip
        Path tempZip = Files.createTempFile("promo-diff", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tempZip))) {
            out.putNextEntry(new ZipEntry("data.json"));
            out.write(objectMapper.writeValueAsBytes(data));
            out.closeEntry();
        }

        try {
            PromotionPreview preview = backupRestorePort.previewPromotion(tenant, tempZip);
            assertThat(preview).isNotNull();
            assertThat(preview.products().added()).isEqualTo(1);
            assertThat(preview.products().removed()).isEqualTo(1);
            assertThat(preview.products().removedNames()).contains(removedProd.getName());
            assertThat(preview.products().changed()).isGreaterThanOrEqualTo(1);
            assertThat(preview.pricesOverwritten()).isGreaterThanOrEqualTo(1);
        } finally {
            Files.deleteIfExists(tempZip);
        }

        // Test version mismatch
        data.setAppVersion("0.0.0-mismatch");
        Path mismatchZip = Files.createTempFile("promo-mismatch", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(mismatchZip))) {
            out.putNextEntry(new ZipEntry("data.json"));
            out.write(objectMapper.writeValueAsBytes(data));
            out.closeEntry();
        }

        try {
            assertThatThrownBy(() -> backupRestorePort.previewPromotion(tenant, mismatchZip))
                    .isInstanceOf(TenantBackupRestorePort.VersionMismatch.class)
                    .hasMessageContaining("0.0.0-mismatch");
        } finally {
            Files.deleteIfExists(mismatchZip);
        }

        // Test null version
        data.setAppVersion(null);
        Path nullVersionZip = Files.createTempFile("promo-null-version", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(nullVersionZip))) {
            out.putNextEntry(new ZipEntry("data.json"));
            out.write(objectMapper.writeValueAsBytes(data));
            out.closeEntry();
        }

        try {
            assertThatThrownBy(() -> backupRestorePort.previewPromotion(tenant, nullVersionZip))
                    .isInstanceOf(TenantBackupRestorePort.VersionMismatch.class);
        } finally {
            Files.deleteIfExists(nullVersionZip);
        }

        // Test corrupted zip / unreadable bundle
        Path corruptedZip = Files.createTempFile("promo-corrupted", ".zip");
        Files.writeString(corruptedZip, "not-a-valid-zip-content");
        try {
            assertThatThrownBy(() -> backupRestorePort.previewPromotion(tenant, corruptedZip))
                    .isInstanceOf(TenantBackupRestorePort.UnreadableBundle.class);
        } finally {
            Files.deleteIfExists(corruptedZip);
        }
    }
}
