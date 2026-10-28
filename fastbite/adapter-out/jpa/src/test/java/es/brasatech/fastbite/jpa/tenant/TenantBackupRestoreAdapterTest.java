package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.customization.CustomizationEntity;
import es.brasatech.fastbite.jpa.customization.CustomizationJpaRepository;
import es.brasatech.fastbite.jpa.group.GroupEntity;
import es.brasatech.fastbite.jpa.group.GroupJpaRepository;
import es.brasatech.fastbite.jpa.product.ProductEntity;
import es.brasatech.fastbite.jpa.product.ProductJpaRepository;
import es.brasatech.fastbite.jpa.table.TableEntity;
import es.brasatech.fastbite.jpa.table.TableJpaRepository;
import es.brasatech.fastbite.jpa.settings.RestaurantSettingsEntity;
import es.brasatech.fastbite.jpa.settings.RestaurantSettingsJpaRepository;
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
import java.util.HashMap;

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
    private TableJpaRepository tableRepository;

    @Autowired
    private RestaurantSettingsJpaRepository settingsRepository;

    @Autowired
    private AppearanceJdbcAdapter appearance;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    private es.brasatech.fastbite.jpa.order.OrderJpaRepository orderRepository;

    @Autowired
    private es.brasatech.fastbite.jpa.user.UserJpaRepository userRepository;

    @Autowired
    private es.brasatech.fastbite.jpa.payment.PaymentConfigJpaRepository paymentConfigRepository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @org.springframework.beans.factory.annotation.Value("${fastbite.version:dev}")
    private String appVersion;

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

    @Test
    void testTableOverridesPreservedInBackupAndStrippedInProductionBundle() {
        String testTenant = "tableoverridestenant";
        tenantProvisionerAdapter.provisionTenant(testTenant);
        TenantContext.setCurrentTenant(testTenant);

        transactionTemplate.execute(status -> {
            RestaurantSettingsEntity settings = settingsRepository.findById(1).orElseGet(RestaurantSettingsEntity::new);
            settings.setTableQrTrusted(true);
            settingsRepository.save(settings);

            TableEntity table = new TableEntity();
            table.setName("Table 1");
            table.setSeats(4);
            table.setStatus(es.brasatech.fastbite.domain.table.TableStatus.AVAILABLE);
            table.setActive(true);
            table.setQrTrusted(true);
            table.setQrTrustedBy("staff-test");
            table.setQrTrustedAt(java.time.LocalDateTime.now());
            tableRepository.save(table);
            return null;
        });

        // 1. Export full backup and restore to a target tenant
        ByteArrayOutputStream backupOut = new ByteArrayOutputStream();
        backupRestoreAdapter.exportBackup(testTenant, backupOut);
        assertTrue(backupOut.size() > 0);

        String restoreBackupTenant = "restorebackuptenant";
        tenantProvisionerAdapter.provisionTenant(restoreBackupTenant);
        backupRestoreAdapter.importRestore(restoreBackupTenant, new ByteArrayInputStream(backupOut.toByteArray()));

        TenantContext.setCurrentTenant(restoreBackupTenant);
        transactionTemplate.execute(status -> {
            TableEntity restoredTable = tableRepository.findAll().stream()
                    .filter(t -> "Table 1".equals(t.getName())).findFirst().orElseThrow();
            assertTrue(restoredTable.getQrTrusted(), "Full backup must preserve table qrTrusted override");
            assertEquals("staff-test", restoredTable.getQrTrustedBy());
            org.junit.jupiter.api.Assertions.assertNotNull(restoredTable.getQrTrustedAt());

            var restoredSettings = settingsRepository.findById(1).orElseThrow();
            assertTrue(restoredSettings.isTableQrTrusted(), "Full backup must preserve restaurantSettings tableQrTrusted");
            return null;
        });

        // 2. Export production bundle and restore to a bundle tenant
        ByteArrayOutputStream bundleOut = new ByteArrayOutputStream();
        backupRestoreAdapter.exportProductionBundle(testTenant, bundleOut);
        assertTrue(bundleOut.size() > 0);

        String restoreBundleTenant = "restorebundletenant";
        tenantProvisionerAdapter.provisionTenant(restoreBundleTenant);
        backupRestoreAdapter.importRestore(restoreBundleTenant, new ByteArrayInputStream(bundleOut.toByteArray()));

        TenantContext.setCurrentTenant(restoreBundleTenant);
        transactionTemplate.execute(status -> {
            TableEntity restoredTable = tableRepository.findAll().stream()
                    .filter(t -> "Table 1".equals(t.getName())).findFirst().orElseThrow();
            org.junit.jupiter.api.Assertions.assertNull(restoredTable.getQrTrusted(), "Production bundle must strip table qrTrusted override");
            org.junit.jupiter.api.Assertions.assertNull(restoredTable.getQrTrustedBy());
            org.junit.jupiter.api.Assertions.assertNull(restoredTable.getQrTrustedAt());

            var restoredSettings = settingsRepository.findById(1).orElseThrow();
            assertTrue(restoredSettings.isTableQrTrusted(), "Production bundle carries restaurant settings");
            return null;
        });
    }

    /** The archive with data.json claiming another FastBite version. */
    /** Strips tableQrTrusted from settings and qrTrusted/qrTrustedBy/qrTrustedAt from tables to simulate legacy archives. */
    private static byte[] stripNewFields(byte[] archive) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(archive));
             var zip = new java.util.zip.ZipOutputStream(out)) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                byte[] content = in.readAllBytes();
                if (entry.getName().equals("data.json")) {
                    String json = new String(content, java.nio.charset.StandardCharsets.UTF_8);
                    json = json.replaceAll("\"tableQrTrusted\"\\s*:\\s*(true|false),?", "");
                    json = json.replaceAll("\"qrTrusted\"\\s*:\\s*(true|false|null),?", "");
                    json = json.replaceAll("\"qrTrustedBy\"\\s*:\\s*(\"[^\"]*\"|null),?", "");
                    json = json.replaceAll("\"qrTrustedAt\"\\s*:\\s*(\"[^\"]*\"|null),?", "");
                    // Clean trailing commas before closing braces if regex left any
                    json = json.replaceAll(",\\s*\\}", "}");
                    content = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                zip.write(content);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void testLegacyArchiveWithoutNewFieldsImportsAsSupervised() throws Exception {
        String testTenant = "legacysourcetenant";
        tenantProvisionerAdapter.provisionTenant(testTenant);
        TenantContext.setCurrentTenant(testTenant);

        transactionTemplate.execute(status -> {
            RestaurantSettingsEntity settings = settingsRepository.findById(1).orElseGet(RestaurantSettingsEntity::new);
            settings.setTableQrTrusted(true);
            settingsRepository.save(settings);

            TableEntity table = new TableEntity();
            table.setName("Legacy Table");
            table.setSeats(4);
            table.setStatus(es.brasatech.fastbite.domain.table.TableStatus.AVAILABLE);
            table.setActive(true);
            table.setQrTrusted(true);
            table.setQrTrustedBy("staff-legacy");
            table.setQrTrustedAt(java.time.LocalDateTime.now());
            tableRepository.save(table);
            return null;
        });

        // 1. Export full backup, strip new fields, restore to new tenant
        ByteArrayOutputStream backupOut = new ByteArrayOutputStream();
        backupRestoreAdapter.exportBackup(testTenant, backupOut);
        byte[] legacyBackup = stripNewFields(backupOut.toByteArray());

        String legacyBackupTenant = "legacybackuptenant";
        tenantProvisionerAdapter.provisionTenant(legacyBackupTenant);
        backupRestoreAdapter.importRestore(legacyBackupTenant, new ByteArrayInputStream(legacyBackup));

        TenantContext.setCurrentTenant(legacyBackupTenant);
        transactionTemplate.execute(status -> {
            var restoredSettings = settingsRepository.findById(1).orElseThrow();
            org.junit.jupiter.api.Assertions.assertFalse(restoredSettings.isTableQrTrusted(),
                    "Legacy backup without tableQrTrusted must default to supervised (false)");

            TableEntity restoredTable = tableRepository.findAll().stream()
                    .filter(t -> "Legacy Table".equals(t.getName())).findFirst().orElseThrow();
            org.junit.jupiter.api.Assertions.assertNull(restoredTable.getQrTrusted(),
                    "Legacy backup without qrTrusted must have null override");
            org.junit.jupiter.api.Assertions.assertNull(restoredTable.getQrTrustedBy());
            org.junit.jupiter.api.Assertions.assertNull(restoredTable.getQrTrustedAt());
            return null;
        });

        // 2. Export production bundle, strip new fields, restore to new tenant
        ByteArrayOutputStream bundleOut = new ByteArrayOutputStream();
        backupRestoreAdapter.exportProductionBundle(testTenant, bundleOut);
        byte[] legacyBundle = stripNewFields(bundleOut.toByteArray());

        String legacyBundleTenant = "legacybundletenant";
        tenantProvisionerAdapter.provisionTenant(legacyBundleTenant);
        backupRestoreAdapter.importRestore(legacyBundleTenant, new ByteArrayInputStream(legacyBundle));

        TenantContext.setCurrentTenant(legacyBundleTenant);
        transactionTemplate.execute(status -> {
            var restoredSettings = settingsRepository.findById(1).orElseThrow();
            org.junit.jupiter.api.Assertions.assertFalse(restoredSettings.isTableQrTrusted(),
                    "Legacy bundle without tableQrTrusted must default to supervised (false)");

            TableEntity restoredTable = tableRepository.findAll().stream()
                    .filter(t -> "Legacy Table".equals(t.getName())).findFirst().orElseThrow();
            org.junit.jupiter.api.Assertions.assertNull(restoredTable.getQrTrusted(),
                    "Legacy bundle without qrTrusted must have null override");
            org.junit.jupiter.api.Assertions.assertNull(restoredTable.getQrTrustedBy());
            org.junit.jupiter.api.Assertions.assertNull(restoredTable.getQrTrustedAt());
            return null;
        });
    }

    @Autowired
    private es.brasatech.fastbite.application.tenant.TenantLocationService tenantLocationService;

    @Test
    void restoringIntoAFreePlanProductionLocationForcesTakeawayOn() {
        String sourceTenant = "bundlesrcfree";
        String destTenant = "bundledestfree";
        tenantProvisionerAdapter.provisionTenant(sourceTenant);
        tenantProvisionerAdapter.provisionTenant(destTenant);
        tenantLocationService.registerLocation("freeowner", destTenant, "FREE");

        TenantContext.setCurrentTenant(sourceTenant);
        transactionTemplate.execute(status -> {
            var s = new es.brasatech.fastbite.jpa.settings.RestaurantSettingsEntity();
            s.setDineIn(true);
            s.setTakeaway(false);
            s.setKdsYellowMinutes(0);
            s.setKdsRedMinutes(0);
            settingsRepository.save(s);
            return null;
        });
        ByteArrayOutputStream bundle = new ByteArrayOutputStream();
        backupRestoreAdapter.exportProductionBundle(sourceTenant, bundle);

        Object originalLock = org.springframework.test.util.ReflectionTestUtils.getField(backupRestoreAdapter, "freePlanLock");
        org.springframework.test.util.ReflectionTestUtils.setField(backupRestoreAdapter, "freePlanLock",
                new es.brasatech.fastbite.application.tenant.FreePlanLock(
                        new es.brasatech.fastbite.application.platform.PlatformEnvironment("production"), tenantLocationService));
        try {
            TenantContext.setCurrentTenant(destTenant);
            backupRestoreAdapter.importProductionBundle(destTenant, new ByteArrayInputStream(bundle.toByteArray()));
            transactionTemplate.execute(status -> {
                var restored = settingsRepository.findById(1).orElseThrow();
                assertTrue(restored.isTakeaway(), "Free-plan production location must force takeaway = true on restore");
                return null;
            });
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(backupRestoreAdapter, "freePlanLock", originalLock);
        }
    }

    @Test
    void productionBundleImportKeepsOrdersStaffPaymentsAndTableTrust() {
        String prodTenant = "prodtenantkeep";
        String sandboxTenant = "sandboxtenantkeep";
        tenantProvisionerAdapter.provisionTenant(prodTenant);
        tenantProvisionerAdapter.provisionTenant(sandboxTenant);

        String orderId = "order-prod-1";
        java.util.concurrent.atomic.AtomicReference<String> tableIdRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> oldTableIdRef = new java.util.concurrent.atomic.AtomicReference<>();

        // 1. Seed production with order, staff user, payment config, table with trust + order
        TenantContext.setCurrentTenant(prodTenant);
        transactionTemplate.execute(status -> {
            // Product to be replaced
            ProductEntity p = new ProductEntity();
            p.setName("Old Burger");
            p.setPrice(BigDecimal.valueOf(10.00));
            p.setActive(true);
            productRepository.save(p);

            // Order with cart item and cart item customization
            entityManager.createNativeQuery("""
                INSERT INTO orders (id, order_number, created_at, updated_at, status, total, payment_status, order_channel, order_language, customer_name, service_type)
                VALUES (:oid, 42, TIMESTAMP '2026-10-20 12:00:00', TIMESTAMP '2026-10-20 12:00:00', 1, 20.00, 1, 3, 'es', 'Alice', 'DINE_IN')
                """).setParameter("oid", orderId).executeUpdate();
            entityManager.createNativeQuery("""
                INSERT INTO cart_items (id, order_id, item_id, name, quantity, price)
                VALUES ('item-1', :oid, 'item-prod', 'Old Burger', 1, 20.00)
                """).setParameter("oid", orderId).executeUpdate();
            entityManager.createNativeQuery("""
                INSERT INTO cart_item_customizations (cart_item_id, id, name, price, quantity)
                VALUES ('item-1', 'cust-1', 'Extra Bacon', 2.00, 1)
                """).executeUpdate();

            // Staff user with WAITER role
            entityManager.createNativeQuery("""
                INSERT INTO users (id, username, password, active, tenant_id)
                VALUES ('staff-1', 'waiter_prod', 'hash', TRUE, :tenant)
                """).setParameter("tenant", prodTenant).executeUpdate();
            entityManager.createNativeQuery("INSERT INTO user_roles (user_id, role) VALUES ('staff-1', 'WAITER')").executeUpdate();

            // Payment config
            entityManager.createNativeQuery("INSERT INTO payment_configs (id, active) VALUES ('pay-1', TRUE)").executeUpdate();
            entityManager.createNativeQuery("INSERT INTO payment_modes (config_id, mode) VALUES ('pay-1', 'CARD')").executeUpdate();
            entityManager.createNativeQuery("INSERT INTO money_denominations (config_id, denomination_value, type) VALUES ('pay-1', 20.00, 'BANKNOTE')").executeUpdate();

            // Order counter
            entityManager.createNativeQuery("UPDATE order_counter SET business_day = :today, last_number = 77 WHERE id = 1")
                    .setParameter("today", java.time.LocalDate.now()).executeUpdate();

            // Surviving table with QR trust and active order
            TableEntity surviving = new TableEntity();
            surviving.setName("Table 1 Production");
            surviving.setSeats(4);
            surviving.setStatus(es.brasatech.fastbite.domain.table.TableStatus.OCCUPIED);
            surviving.setActive(true);
            surviving.setQrTrusted(true);
            surviving.setQrTrustedBy("staff-alice");
            surviving.setQrTrustedAt(java.time.LocalDateTime.of(2026, 10, 20, 10, 0));
            surviving.setOrderIds(new java.util.ArrayList<>(List.of(orderId)));
            surviving = tableRepository.save(surviving);
            tableIdRef.set(surviving.getId());

            // Old table that won't be in bundle
            TableEntity oldTable = new TableEntity();
            oldTable.setName("Table Old");
            oldTable.setSeats(2);
            oldTable.setStatus(es.brasatech.fastbite.domain.table.TableStatus.AVAILABLE);
            oldTable.setActive(true);
            oldTable = tableRepository.save(oldTable);
            oldTableIdRef.set(oldTable.getId());
            return null;
        });

        String tableId = tableIdRef.get();
        String oldTableId = oldTableIdRef.get();
        java.util.concurrent.atomic.AtomicReference<String> newTableIdRef = new java.util.concurrent.atomic.AtomicReference<>();

        // 2. Prepare Sandbox with new menu and tables (surviving + new)
        TenantContext.setCurrentTenant(sandboxTenant);
        transactionTemplate.execute(status -> {
            ProductEntity np = new ProductEntity();
            np.setName("New Artisan Pizza");
            np.setPrice(BigDecimal.valueOf(15.00));
            np.setActive(true);
            productRepository.save(np);

            GroupEntity g = new GroupEntity();
            g.setName("Pizzas");
            g.setProducts(List.of(np.getId()));
            groupRepository.save(g);

            entityManager.createNativeQuery("""
                INSERT INTO dining_tables (id, name, seats, status, active)
                VALUES (:id, 'Table 1 Sandbox Renamed', 6, 'AVAILABLE', TRUE)
                """).setParameter("id", tableId).executeUpdate();

            TableEntity newTable = new TableEntity();
            newTable.setName("Table 3 Brand New");
            newTable.setSeats(2);
            newTable.setStatus(es.brasatech.fastbite.domain.table.TableStatus.AVAILABLE);
            newTable.setActive(true);
            newTable = tableRepository.save(newTable);
            newTableIdRef.set(newTable.getId());
            return null;
        });

        String newTableId = newTableIdRef.get();

        ByteArrayOutputStream bundleOut = new ByteArrayOutputStream();
        backupRestoreAdapter.exportProductionBundle(sandboxTenant, bundleOut);
        assertTrue(bundleOut.size() > 0);

        // 3. Import bundle into production (direct call, NO surrounding transaction)
        TenantContext.setCurrentTenant(prodTenant);
        backupRestoreAdapter.importProductionBundle(prodTenant, new ByteArrayInputStream(bundleOut.toByteArray()));

        // 4. Assertions on production
        TenantContext.setCurrentTenant(prodTenant);
        transactionTemplate.execute(status -> {
            // Orders survived intact
            assertEquals(1, orderRepository.count(), "Production orders must survive bundle import");
            var order = orderRepository.findById(orderId).orElseThrow();
            assertEquals("Alice", order.getCustomerName());
            assertEquals(1, ((Number) entityManager.createNativeQuery("SELECT count(*) FROM cart_items WHERE order_id = :oid").setParameter("oid", orderId).getSingleResult()).intValue());
            assertEquals(1, ((Number) entityManager.createNativeQuery("SELECT count(*) FROM cart_item_customizations WHERE cart_item_id = 'item-1'").getSingleResult()).intValue());

            // Order counter survived
            int counter = ((Number) entityManager.createNativeQuery("SELECT last_number FROM order_counter WHERE id = 1").getSingleResult()).intValue();
            assertEquals(77, counter, "Order counter must not be reset by bundle import");

            // Staff survived
            assertEquals(1, userRepository.count());
            var user = userRepository.findById("staff-1").orElseThrow();
            assertEquals("waiter_prod", user.getUsername());
            assertEquals("WAITER", entityManager.createNativeQuery("SELECT role FROM user_roles WHERE user_id = 'staff-1'").getSingleResult());

            // Payment settings survived
            assertEquals(1, paymentConfigRepository.count());
            assertEquals("CARD", entityManager.createNativeQuery("SELECT mode FROM payment_modes WHERE config_id = 'pay-1'").getSingleResult());
            assertEquals(0, new BigDecimal("20").compareTo((BigDecimal) entityManager.createNativeQuery("SELECT denomination_value FROM money_denominations WHERE config_id = 'pay-1'").getSingleResult()));

            // Old table is deleted
            assertTrue(tableRepository.findById(oldTableId).isEmpty(), "Tables not in bundle must be deleted");

            // New table is added with null qrTrusted
            var addedTable = tableRepository.findById(newTableId).orElseThrow();
            assertEquals("Table 3 Brand New", addedTable.getName());
            org.junit.jupiter.api.Assertions.assertNull(addedTable.getQrTrusted(), "New tables from bundle have null qrTrusted");

            // Surviving table has updated name/seats, but preserved trust and active orders
            var survivingTable = tableRepository.findById(tableId).orElseThrow();
            assertEquals("Table 1 Sandbox Renamed", survivingTable.getName());
            assertEquals(6, survivingTable.getSeats());
            assertTrue(survivingTable.getQrTrusted(), "Surviving table must retain production qrTrusted");
            assertEquals("staff-alice", survivingTable.getQrTrustedBy());
            assertEquals(java.time.LocalDateTime.of(2026, 10, 20, 10, 0), survivingTable.getQrTrustedAt());
            assertEquals(List.of(orderId), survivingTable.getOrderIds(), "Surviving table must keep production orderIds");
            assertEquals(es.brasatech.fastbite.domain.table.TableStatus.OCCUPIED, survivingTable.getStatus(), "Surviving table with orders stays OCCUPIED");

            // Menu reflects bundle
            var prods = productRepository.findAll();
            assertEquals(1, prods.size());
            assertEquals("New Artisan Pizza", prods.get(0).getName());
            var groups = groupRepository.findAll();
            assertEquals(1, groups.size());
            assertEquals("Pizzas", groups.get(0).getName());

            return null;
        });
    }

    @Test
    void survivingTableAndProductTranslationChangesUpdateCleanlyWithoutDuplicates() {
        String prodTenant = "prodtenanttrans";
        String sandboxTenant = "sandboxtenanttrans";
        tenantProvisionerAdapter.provisionTenant(prodTenant);
        tenantProvisionerAdapter.provisionTenant(sandboxTenant);

        java.util.concurrent.atomic.AtomicReference<String> tableIdRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> prodIdRef = new java.util.concurrent.atomic.AtomicReference<>();

        // Production setup
        TenantContext.setCurrentTenant(prodTenant);
        transactionTemplate.execute(status -> {
            TableEntity table = new TableEntity();
            table.setName("Mesa 1");
            table.setSeats(4);
            table.setStatus(es.brasatech.fastbite.domain.table.TableStatus.AVAILABLE);
            table.setActive(true);
            table.setTranslations(new HashMap<>(java.util.Map.of("en", java.util.Map.of("name", "Table 1"))));
            table = tableRepository.save(table);
            tableIdRef.set(table.getId());

            ProductEntity prod = new ProductEntity();
            prod.setName("Hamburguesa");
            prod.setPrice(BigDecimal.valueOf(8.50));
            prod.setActive(true);
            prod.setTranslations(new HashMap<>(java.util.Map.of("en", java.util.Map.of("name", "Burger"))));
            prod = productRepository.save(prod);
            prodIdRef.set(prod.getId());
            return null;
        });

        String tableId = tableIdRef.get();
        String productId = prodIdRef.get();

        // Sandbox setup with renamed entities and multiple translations
        TenantContext.setCurrentTenant(sandboxTenant);
        transactionTemplate.execute(status -> {
            entityManager.createNativeQuery("""
                INSERT INTO dining_tables (id, name, seats, status, active, translations)
                VALUES (:id, 'Mesa 1 Renovada', 4, 'AVAILABLE', TRUE, :trans)
                """).setParameter("id", tableId)
                .setParameter("trans", "{\"en\":{\"name\":\"Table 1 Renovated\"},\"pt\":{\"name\":\"Mesa 1 Renovada PT\"}}")
                .executeUpdate();

            entityManager.createNativeQuery("""
                INSERT INTO products (id, name, price, active, translations)
                VALUES (:id, 'Hamburguesa Especial', 10.50, TRUE, :trans)
                """).setParameter("id", productId)
                .setParameter("trans", "{\"en\":{\"name\":\"Special Burger\"},\"pt\":{\"name\":\"Hambúrguer Especial\"}}")
                .executeUpdate();
            return null;
        });

        ByteArrayOutputStream bundleOut = new ByteArrayOutputStream();
        backupRestoreAdapter.exportProductionBundle(sandboxTenant, bundleOut);

        TenantContext.setCurrentTenant(prodTenant);
        backupRestoreAdapter.importProductionBundle(prodTenant, new ByteArrayInputStream(bundleOut.toByteArray()));

        TenantContext.setCurrentTenant(prodTenant);
        transactionTemplate.execute(status -> {
            // Check table
            assertEquals(1, tableRepository.count(), "Must have exactly 1 table row (no duplicate)");
            var table = tableRepository.findById(tableId).orElseThrow();
            assertEquals("Mesa 1 Renovada", table.getName());
            assertEquals("Table 1 Renovated", table.getTranslations().get("en").get("name"));
            assertEquals("Mesa 1 Renovada PT", table.getTranslations().get("pt").get("name"));

            // Check product
            assertEquals(1, productRepository.count(), "Must have exactly 1 product row (no duplicate)");
            var prod = productRepository.findById(productId).orElseThrow();
            assertEquals("Hamburguesa Especial", prod.getName());
            assertEquals("Special Burger", prod.getTranslations().get("en").get("name"));
            assertEquals("Hambúrguer Especial", prod.getTranslations().get("pt").get("name"));

            return null;
        });
    }

    @Test
    void restorePromotionSnapshotBypassesVersionCheck() throws Exception {
        String prodTenant = "prodpromotionsnapshot";
        String sandboxTenant = "sandboxpromotionsnapshot";
        tenantProvisionerAdapter.provisionTenant(prodTenant);
        tenantProvisionerAdapter.provisionTenant(sandboxTenant);

        TenantContext.setCurrentTenant(sandboxTenant);
        backupRestoreAdapter.importDemoTemplate(sandboxTenant, "cafe");

        ByteArrayOutputStream bundleOut = new ByteArrayOutputStream();
        backupRestoreAdapter.exportProductionBundle(sandboxTenant, bundleOut);
        byte[] alteredZip = withVersion(bundleOut.toByteArray(), "0.0.1");

        TenantContext.setCurrentTenant(prodTenant);
        // importProductionBundle rejects version mismatch
        assertThrows(es.brasatech.fastbite.application.tenant.TenantBackupRestorePort.VersionMismatch.class,
                () -> backupRestoreAdapter.importProductionBundle(prodTenant, new ByteArrayInputStream(alteredZip)));

        // restorePromotionSnapshot succeeds even if FastBite version differed
        var result = backupRestoreAdapter.restorePromotionSnapshot(prodTenant, new ByteArrayInputStream(alteredZip));
        org.junit.jupiter.api.Assertions.assertNotNull(result);

        TenantContext.setCurrentTenant(prodTenant);
        transactionTemplate.execute(status -> {
            assertTrue(productRepository.count() > 0, "Products should be restored by promotion snapshot");
            return null;
        });
    }

    @Test
    void bundleImportWithEmptyIncomingTablesDeletesAllTablesWithoutSqlSyntaxError() {
        String prodTenant = "prodemptytables";
        String sandboxTenant = "sandboxemptytables";
        tenantProvisionerAdapter.provisionTenant(prodTenant);
        tenantProvisionerAdapter.provisionTenant(sandboxTenant);

        TenantContext.setCurrentTenant(prodTenant);
        transactionTemplate.execute(status -> {
            TableEntity table = new TableEntity();
            table.setName("Existing Table");
            table.setSeats(2);
            table.setStatus(es.brasatech.fastbite.domain.table.TableStatus.AVAILABLE);
            table.setActive(true);
            tableRepository.save(table);
            return null;
        });

        // Sandbox has product but NO tables
        TenantContext.setCurrentTenant(sandboxTenant);
        transactionTemplate.execute(status -> {
            ProductEntity p = new ProductEntity();
            p.setName("Only Product");
            p.setPrice(BigDecimal.TEN);
            p.setActive(true);
            productRepository.save(p);
            return null;
        });

        ByteArrayOutputStream bundleOut = new ByteArrayOutputStream();
        backupRestoreAdapter.exportProductionBundle(sandboxTenant, bundleOut);

        TenantContext.setCurrentTenant(prodTenant);
        backupRestoreAdapter.importProductionBundle(prodTenant, new ByteArrayInputStream(bundleOut.toByteArray()));

        TenantContext.setCurrentTenant(prodTenant);
        transactionTemplate.execute(status -> {
            assertEquals(0, tableRepository.count(), "All tables must be deleted when incoming bundle has no tables");
            assertEquals(1, productRepository.count());
            return null;
        });
    }

    @Test
    void bundleCarryingOccupiedTablesImportsIntoProductionAsAvailableWithoutSandboxOrders() throws Exception {
        String prodTenant = "prodtableavail";
        tenantProvisionerAdapter.provisionTenant(prodTenant);

        java.util.concurrent.atomic.AtomicReference<String> survivingTableIdRef = new java.util.concurrent.atomic.AtomicReference<>();
        TenantContext.setCurrentTenant(prodTenant);
        transactionTemplate.execute(status -> {
            TableEntity surviving = new TableEntity();
            surviving.setName("Terrace 1");
            surviving.setSeats(4);
            surviving.setStatus(es.brasatech.fastbite.domain.table.TableStatus.AVAILABLE);
            surviving.setActive(true);
            surviving = tableRepository.save(surviving);
            survivingTableIdRef.set(surviving.getId());
            return null;
        });

        String survivingTableId = survivingTableIdRef.get();
        String newTableId = java.util.UUID.randomUUID().toString();

        // Build an archive by hand simulating an older bundle where sandbox tables carried orders and OCCUPIED
        String bundleJson = """
            {
              "formatVersion": 3,
              "appVersion": "%s",
              "productionBundle": true,
              "tables": [
                {
                  "id": "%s",
                  "name": "Terrace 1 Updated",
                  "seats": 4,
                  "status": "OCCUPIED",
                  "active": true,
                  "orderIds": ["sandbox-test-order-1"]
                },
                {
                  "id": "%s",
                  "name": "Bar 2",
                  "seats": 2,
                  "status": "OCCUPIED",
                  "active": true,
                  "orderIds": ["sandbox-test-order-2"]
                }
              ]
            }
            """.formatted(appVersion, survivingTableId, newTableId);

        ByteArrayOutputStream zipOut = new ByteArrayOutputStream();
        try (var zos = new java.util.zip.ZipOutputStream(zipOut)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("data.json"));
            zos.write(bundleJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        // Import into production
        TenantContext.setCurrentTenant(prodTenant);
        backupRestoreAdapter.importProductionBundle(prodTenant, new ByteArrayInputStream(zipOut.toByteArray()));

        // Assert
        TenantContext.setCurrentTenant(prodTenant);
        transactionTemplate.execute(status -> {
            assertEquals(2, tableRepository.count());

            var surviving = tableRepository.findById(survivingTableId).orElseThrow();
            assertEquals("Terrace 1 Updated", surviving.getName());
            assertEquals(es.brasatech.fastbite.domain.table.TableStatus.AVAILABLE, surviving.getStatus(),
                    "Surviving table that was AVAILABLE in production must stay AVAILABLE");
            assertTrue(surviving.getOrderIds().isEmpty(), "Surviving table must not take sandbox orders");

            var newTable = tableRepository.findById(newTableId).orElseThrow();
            assertEquals("Bar 2", newTable.getName());
            assertEquals(es.brasatech.fastbite.domain.table.TableStatus.AVAILABLE, newTable.getStatus(),
                    "New table from bundle must always be AVAILABLE");
            assertTrue(newTable.getOrderIds().isEmpty(), "New table must have empty orderIds");

            int tableOrdersCount = ((Number) entityManager.createNativeQuery("SELECT count(*) FROM table_orders").getSingleResult()).intValue();
            assertEquals(0, tableOrdersCount, "No sandbox order references should exist in table_orders");

            return null;
        });
    }

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
