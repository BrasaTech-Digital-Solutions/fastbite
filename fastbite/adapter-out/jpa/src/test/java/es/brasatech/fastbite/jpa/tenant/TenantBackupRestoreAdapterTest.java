package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.customization.CustomizationEntity;
import es.brasatech.fastbite.jpa.customization.CustomizationJpaRepository;
import es.brasatech.fastbite.jpa.group.GroupEntity;
import es.brasatech.fastbite.jpa.group.GroupJpaRepository;
import es.brasatech.fastbite.jpa.product.ProductEntity;
import es.brasatech.fastbite.jpa.product.ProductJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = es.brasatech.fastbite.jpa.TestConfig.class)
class TenantBackupRestoreAdapterTest {

    @Autowired
    private TenantBackupRestoreAdapter backupRestoreAdapter;

    @Autowired
    private TenantProvisionerAdapter tenantProvisionerAdapter;

    @Autowired
    private GroupJpaRepository groupRepository;

    @Autowired
    private ProductJpaRepository productRepository;

    @Autowired
    private CustomizationJpaRepository customizationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private AppearanceJdbcAdapter appearance;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void testBackupCleanRestoreLifecycle() {
        String testTenant = "backuptesttenant";
        tenantProvisionerAdapter.provisionTenant(testTenant);
        TenantContext.setCurrentTenant(testTenant);

        // 1. Populate test data under transaction
        transactionTemplate.execute(status -> {
            // Add customization
            CustomizationEntity c = new CustomizationEntity();
            c.setName("Extra Cheese");
            c.setType("radio");
            customizationRepository.save(c);

            // Add product
            ProductEntity p = new ProductEntity();
            p.setName("Double Cheeseburger");
            p.setPrice(BigDecimal.valueOf(11.50));
            p.setActive(true);
            productRepository.save(p);

            // Add group
            GroupEntity g = new GroupEntity();
            g.setName("Burgers Menu");
            g.setDescription("Beef burgers");
            g.setProducts(List.of(p.getId()));
            groupRepository.save(g);

            return null;
        });

        // 2. Export backup
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        backupRestoreAdapter.exportBackup(testTenant, baos);
        byte[] backupBytes = baos.toByteArray();
        assertTrue(backupBytes.length > 0);

        // 3. Clean tenant data
        backupRestoreAdapter.cleanTenantData(testTenant);

        // 4. Verify data is gone
        TenantContext.setCurrentTenant(testTenant);
        transactionTemplate.execute(status -> {
            assertEquals(0, groupRepository.count());
            assertEquals(0, productRepository.count());
            assertEquals(0, customizationRepository.count());
            return null;
        });

        // 5. Restore data
        ByteArrayInputStream bais = new ByteArrayInputStream(backupBytes);
        backupRestoreAdapter.importRestore(testTenant, bais);

        // 6. Verify data is fully restored
        TenantContext.setCurrentTenant(testTenant);
        transactionTemplate.execute(status -> {
            List<GroupEntity> restoredGroups = groupRepository.findAll();
            assertEquals(1, restoredGroups.size());
            assertEquals("Burgers Menu", restoredGroups.get(0).getName());

            List<ProductEntity> restoredProducts = productRepository.findAll();
            assertEquals(1, restoredProducts.size());
            assertEquals("Double Cheeseburger", restoredProducts.get(0).getName());

            List<CustomizationEntity> restoredCustoms = customizationRepository.findAll();
            assertEquals(1, restoredCustoms.size());
            assertEquals("Extra Cheese", restoredCustoms.get(0).getName());

            return null;
        });
    }

    @Test
    void testAvailableTemplatesTrilingual() {
        var enTemplates = backupRestoreAdapter.getAvailableTemplates(java.util.Locale.ENGLISH);
        assertTrue(enTemplates.size() >= 2);
        var cafeEn = enTemplates.stream().filter(t -> t.id().equals("cafe")).findFirst().orElseThrow();
        assertEquals("Specialty Café & Bakery", cafeEn.name());

        var esTemplates = backupRestoreAdapter.getAvailableTemplates(java.util.Locale.forLanguageTag("es"));
        var cafeEs = esTemplates.stream().filter(t -> t.id().equals("cafe")).findFirst().orElseThrow();
        assertEquals("Cafetería de Especialidad & Bakery", cafeEs.name());

        var ptTemplates = backupRestoreAdapter.getAvailableTemplates(java.util.Locale.forLanguageTag("pt"));
        var cafePt = ptTemplates.stream().filter(t -> t.id().equals("cafe")).findFirst().orElseThrow();
        assertEquals("Cafetaria de Especialidade & Padaria", cafePt.name());
    }

    @Test
    void testImportCafeDemoTemplate() {
        String testTenant = "cafedemotenant";
        tenantProvisionerAdapter.provisionTenant(testTenant);
        TenantContext.setCurrentTenant(testTenant);

        backupRestoreAdapter.importDemoTemplate(testTenant, "cafe");

        TenantContext.setCurrentTenant(testTenant);
        transactionTemplate.execute(status -> {
            var products = productRepository.findAll();
            assertTrue(products.size() >= 15);
            boolean hasBombon = products.stream().anyMatch(p -> p.getName().toLowerCase().contains("bombón") || p.getName().toLowerCase().contains("bombon"));
            assertTrue(hasBombon, "Should contain Café Bombón");

            var groups = groupRepository.findAll();
            assertTrue(groups.size() >= 5);

            var customs = customizationRepository.findAll();
            assertTrue(customs.size() >= 3);
            return null;
        });
    }

    @Test
    void testImportMichelinDemoTemplate() {
        String testTenant = "michelintenant";
        tenantProvisionerAdapter.provisionTenant(testTenant);
        TenantContext.setCurrentTenant(testTenant);

        backupRestoreAdapter.importDemoTemplate(testTenant, "michelin");

        TenantContext.setCurrentTenant(testTenant);
        transactionTemplate.execute(status -> {
            var products = productRepository.findAll();
            assertTrue(products.size() >= 12);
            boolean hasWagyu = products.stream().anyMatch(p -> p.getName().toLowerCase().contains("wagyu"));
            assertTrue(hasWagyu, "Should contain A5 Wagyu Carpaccio");

            boolean hasLobster = products.stream().anyMatch(p -> p.getName().toLowerCase().contains("lobster") || p.getName().toLowerCase().contains("bogavante"));
            assertTrue(hasLobster, "Should contain Blue Lobster");

            var groups = groupRepository.findAll();
            assertEquals(5, groups.size());

            var customs = customizationRepository.findAll();
            assertTrue(customs.size() >= 3);
            return null;
        });
    }

    @Test
    void testProductionBundleExcludesOrdersAndAccounts() {
        String testTenant = "bundletenant";
        tenantProvisionerAdapter.provisionTenant(testTenant);
        TenantContext.setCurrentTenant(testTenant);

        backupRestoreAdapter.importDemoTemplate(testTenant, "kebab");

        ByteArrayOutputStream bundleOut = new ByteArrayOutputStream();
        backupRestoreAdapter.exportProductionBundle(testTenant, bundleOut);
        assertTrue(bundleOut.size() > 0);

        var preview = backupRestoreAdapter.previewBackup(testTenant, new ByteArrayInputStream(bundleOut.toByteArray()));
        assertTrue(preview.products() > 0);
        assertEquals(0, preview.orders(), "Production bundle must not contain orders");
        assertEquals(0, preview.accounts(), "Production bundle must not contain accounts");
    }

    @Test
    void aProductionBundleIsOnlyImportedByTheVersionThatExportedIt() throws Exception {
        String sandbox = "bundlesandbox";
        String production = "bundleproduction";
        tenantProvisionerAdapter.provisionTenant(sandbox);
        tenantProvisionerAdapter.provisionTenant(production);
        TenantContext.setCurrentTenant(sandbox);
        backupRestoreAdapter.importDemoTemplate(sandbox, "kebab");
        ByteArrayOutputStream bundle = new ByteArrayOutputStream();
        backupRestoreAdapter.exportProductionBundle(sandbox, bundle);

        TenantContext.setCurrentTenant(production);
        backupRestoreAdapter.importProductionBundle(production, new ByteArrayInputStream(bundle.toByteArray()));

        // The same bundle, as if exported by another release
        byte[] other = withVersion(bundle.toByteArray(), "0.9.0");
        var refused = assertThrows(es.brasatech.fastbite.application.tenant.TenantBackupRestorePort.VersionMismatch.class,
                () -> backupRestoreAdapter.importProductionBundle(production, new ByteArrayInputStream(other)));
        assertTrue(refused.getMessage().contains("0.9.0"), refused.getMessage());
        // Restored through the console's restore, a bundle is still a bundle
        assertThrows(es.brasatech.fastbite.application.tenant.TenantBackupRestorePort.VersionMismatch.class,
                () -> backupRestoreAdapter.importRestore(production, new ByteArrayInputStream(other)));
        // A backup of another version is restored as before
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        TenantContext.setCurrentTenant(sandbox);
        backupRestoreAdapter.exportBackup(sandbox, backup);
        TenantContext.setCurrentTenant(production);
        backupRestoreAdapter.importRestore(production, new ByteArrayInputStream(withVersion(backup.toByteArray(), "0.9.0")));
    }

    @Test
    void theLookChosenInTheSandboxGoesToProductionWithTheMenu() {
        String sandbox = "themesandbox";
        String production = "themeproduction";
        for (String tenant : new String[] {sandbox, production}) {
            tenantProvisionerAdapter.provisionTenant(tenant);
            jdbc.update("INSERT INTO public.tenant_locations (id, owner_username, tenant_id) VALUES (?, 'themeowner', ?)",
                    java.util.UUID.randomUUID().toString(), tenant);
        }
        appearance.saveLocationTheme(sandbox, "cafe");
        TenantContext.setCurrentTenant(sandbox);
        backupRestoreAdapter.importDemoTemplate(sandbox, "cafe");
        ByteArrayOutputStream bundle = new ByteArrayOutputStream();
        backupRestoreAdapter.exportProductionBundle(sandbox, bundle);

        TenantContext.setCurrentTenant(production);
        backupRestoreAdapter.importProductionBundle(production, new ByteArrayInputStream(bundle.toByteArray()));

        assertEquals("cafe", appearance.locationTheme(production));
        // A demo template keeps the theme the owner chose for it
        backupRestoreAdapter.importDemoTemplate(production, "kebab");
        assertEquals("cafe", appearance.locationTheme(production));
    }

    /** The archive with data.json claiming another FastBite version. */
    private static byte[] withVersion(byte[] archive, String version) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(archive));
             var zip = new java.util.zip.ZipOutputStream(out)) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                byte[] content = in.readAllBytes();
                if (entry.getName().equals("data.json")) {
                    content = new String(content, java.nio.charset.StandardCharsets.UTF_8)
                            .replaceFirst("\"appVersion\"\\s*:\\s*\"[^\"]*\"", "\"appVersion\" : \"" + version + "\"")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                zip.write(content);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
