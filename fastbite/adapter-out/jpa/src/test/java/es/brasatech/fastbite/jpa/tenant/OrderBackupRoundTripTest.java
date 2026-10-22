package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.TestConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = TestConfig.class, properties = "image.upload.directory=target/backup-round-trip-media")
class OrderBackupRoundTripTest {
    @Autowired TenantBackupRestoreAdapter backups;
    @Autowired TenantProvisionerAdapter provisioner;
    @Autowired TransactionTemplate transactions;
    @Autowired EntityManager em;
    private static final Path MEDIA = Path.of("target/backup-round-trip-media");

    @AfterEach void clear() { TenantContext.clear(); }

    private String tenant() {
        String tenant = "backup" + UUID.randomUUID().toString().replace("-", "");
        provisioner.provisionTenant(tenant);
        return tenant;
    }

    private <T> T inTenant(String tenant, Supplier<T> work) {
        String previous = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(tenant);
        try { return transactions.execute(status -> work.get()); }
        finally { if (previous == null) TenantContext.clear(); else TenantContext.setCurrentTenant(previous); }
    }

    private void seed(String tenant, String customer) throws Exception {
        inTenant(tenant, () -> {
            em.createNativeQuery("INSERT INTO orders (id, order_number, created_at, updated_at, status, total, payment_status, order_channel, order_language, customer_name, service_type) VALUES ('paid', 42, TIMESTAMP '2026-10-01 12:34:56.123456', TIMESTAMP '2026-10-01 13:00:00', 5, 25.50, 1, 3, 'es', :customer, 'DINE_IN')")
                    .setParameter("customer", customer).executeUpdate();
            em.createNativeQuery("INSERT INTO orders (id, order_number, created_at, updated_at, status, total, payment_status, order_channel, order_language, customer_name, service_type) VALUES ('unpaid', 43, TIMESTAMP '2026-10-01 14:00:00', TIMESTAMP '2026-10-01 14:00:00', 0, 10.00, 0, 0, 'en', :customer, 'TAKEAWAY')")
                    .setParameter("customer", customer).executeUpdate();
            em.createNativeQuery("INSERT INTO cart_items (id, order_id, item_id, name, quantity, price) VALUES ('line', 'paid', 'burger', 'Burger', 2, 12.75)").executeUpdate();
            em.createNativeQuery("INSERT INTO cart_item_customizations (cart_item_id, id, name, price, quantity) VALUES ('line', 'cheese', 'Cheese', 1.25, 1)").executeUpdate();
            em.createNativeQuery("INSERT INTO dining_tables (id, name, seats, status, active) VALUES ('table', 'Terrace', 4, 'AVAILABLE', TRUE)").executeUpdate();
            em.createNativeQuery("INSERT INTO table_orders (table_id, order_id) VALUES ('table', 'paid')").executeUpdate();
            em.createNativeQuery("INSERT INTO payment_configs (id, active) VALUES ('payment', TRUE)").executeUpdate();
            em.createNativeQuery("INSERT INTO payment_modes (config_id, mode) VALUES ('payment', 'CASH')").executeUpdate();
            em.createNativeQuery("INSERT INTO money_denominations (config_id, denomination_value, type) VALUES ('payment', 5.00, 'BANKNOTE')").executeUpdate();
            em.createNativeQuery("INSERT INTO restaurant_settings (id, dine_in, takeaway, kds_yellow_minutes, kds_red_minutes) VALUES (1, FALSE, TRUE, 6, 12)").executeUpdate();
            em.createNativeQuery("UPDATE order_counter SET business_day = :today, last_number = 90 WHERE id = 1")
                    .setParameter("today", java.time.LocalDate.now(java.time.ZoneId.of("Europe/Madrid"))).executeUpdate();
            return null;
        });
        Files.createDirectories(MEDIA.resolve(tenant));
        Files.writeString(MEDIA.resolve(tenant).resolve("image.txt"), customer);
    }

    private byte[] export(String tenant) {
        var bytes = new ByteArrayOutputStream();
        backups.exportBackup(tenant, bytes);
        return bytes.toByteArray();
    }

    private static String json(byte[] zip) throws Exception {
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
                if (entry.getName().equals("data.json")) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("No data.json");
    }

    private Object scalar(String sql) { return em.createNativeQuery(sql).getSingleResult(); }

    @Test void paidAndUnpaidOrdersAndPaymentCollectionsRoundTrip() throws Exception {
        String tenant = tenant();
        seed(tenant, "Customer A");
        TenantContext.setCurrentTenant(tenant);
        byte[] zip = export(tenant);
        assertTrue(json(zip).contains("2026-10-01T12:34:56.123456"));
        inTenant(tenant, () -> {
            em.createNativeQuery("UPDATE order_counter SET last_number = 0 WHERE id = 1").executeUpdate();
            em.createNativeQuery("UPDATE restaurant_settings SET kds_yellow_minutes = 99 WHERE id = 1").executeUpdate();
            return null;
        });
        backups.importRestore(tenant, new ByteArrayInputStream(zip));
        inTenant(tenant, () -> {
            assertEquals(2L, ((Number) scalar("SELECT count(*) FROM orders")).longValue());
            assertEquals(1, ((Number) scalar("SELECT payment_status FROM orders WHERE id = 'paid'")).intValue());
            assertEquals(0, ((Number) scalar("SELECT payment_status FROM orders WHERE id = 'unpaid'")).intValue());
            assertEquals("TAKEAWAY", scalar("SELECT service_type FROM orders WHERE id = 'unpaid'"));
            assertEquals("2026-10-01T12:34:56.123456", scalar("SELECT created_at FROM orders WHERE id = 'paid'").toString().replace(' ', 'T'));
            assertEquals("25.50", scalar("SELECT total FROM orders WHERE id = 'paid'").toString());
            assertEquals("paid", scalar("SELECT order_id FROM cart_items WHERE id = 'line'"));
            assertEquals("1.25", scalar("SELECT price FROM cart_item_customizations WHERE cart_item_id = 'line'").toString());
            assertEquals("paid", scalar("SELECT order_id FROM table_orders WHERE table_id = 'table'"));
            assertEquals("CASH", scalar("SELECT mode FROM payment_modes WHERE config_id = 'payment'"));
            assertEquals(90, ((Number) scalar("SELECT last_number FROM order_counter WHERE id = 1")).intValue());
            assertEquals(6, ((Number) scalar("SELECT kds_yellow_minutes FROM restaurant_settings WHERE id = 1")).intValue());
            assertEquals(0, new java.math.BigDecimal("5").compareTo((java.math.BigDecimal) scalar("SELECT denomination_value FROM money_denominations WHERE config_id = 'payment'")));
            return null;
        });
        assertEquals("Customer A", Files.readString(MEDIA.resolve(tenant).resolve("image.txt")));
    }

    @Test void explicitTenantWinsEvenInsideAnotherTenantsTransaction() throws Exception {
        String first = tenant(), second = tenant();
        seed(first, "Customer A"); seed(second, "Customer B");
        TenantContext.setCurrentTenant(second);
        byte[] zip = inTenant(second, () -> export(first));
        assertTrue(json(zip).contains("Customer A"));
        assertFalse(json(zip).contains("Customer B"));
        inTenant(second, () -> { backups.importRestore(first, new ByteArrayInputStream(zip)); return null; });
        assertEquals(second, TenantContext.getCurrentTenant());
        assertEquals("Customer B", inTenant(second, () -> scalar("SELECT customer_name FROM orders WHERE id = 'paid'")));
        assertEquals("Customer B", Files.readString(MEDIA.resolve(second).resolve("image.txt")));
        inTenant(second, () -> { backups.cleanTenantData(first); return null; });
        assertEquals(0L, inTenant(first, () -> ((Number) scalar("SELECT count(*) FROM orders")).longValue()));
        assertEquals(2L, inTenant(second, () -> ((Number) scalar("SELECT count(*) FROM orders")).longValue()));
    }

    private static byte[] archive(String data, String mediaPath) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("data.json"));
            zip.write(data.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(mediaPath));
            zip.write("replacement".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test void corruptUnsafeAndDatabaseInvalidArchivesLeaveDataAndMediaIntact() throws Exception {
        String target = tenant(), other = tenant();
        seed(target, "Original"); seed(other, "Untouched");
        TenantContext.setCurrentTenant(other);
        byte[][] archives = {
                archive("not-json", "media/image.txt"),
                archive("{}", "media/../../" + other + "/image.txt"),
                archive("{\"formatVersion\":999}", "media/image.txt"),
                archive("{\"orders\":[{\"id\":\"invalid\",\"items\":[],\"total\":null}]}", "media/image.txt")
        };
        for (byte[] zip : archives) {
            assertThrows(RuntimeException.class, () -> backups.importRestore(target, new ByteArrayInputStream(zip)));
            assertEquals(other, TenantContext.getCurrentTenant());
            assertEquals("Original", inTenant(target, () -> scalar("SELECT customer_name FROM orders WHERE id = 'paid'")));
            assertEquals("Original", Files.readString(MEDIA.resolve(target).resolve("image.txt")));
            assertEquals("Untouched", Files.readString(MEDIA.resolve(other).resolve("image.txt")));
        }
    }

    @Test void restoringAnOlderBackupDoesNotRewindNumbersAlreadyIssuedToday() throws Exception {
        String target = tenant();
        seed(target, "Original");
        byte[] zip = export(target);
        inTenant(target, () -> {
            em.createNativeQuery("UPDATE order_counter SET last_number = 120 WHERE id = 1").executeUpdate();
            return null;
        });
        backups.importRestore(target, new ByteArrayInputStream(zip));
        assertEquals(120, inTenant(target, () -> ((Number) scalar("SELECT last_number FROM order_counter WHERE id = 1")).intValue()));
    }

    @Test void demoRestoreAndCleanPreservePlatformOwnersIncludingThoseCreatedAfterBackup() throws Exception {
        provisioner.provisionDemoTenant();
        String ownerId = UUID.randomUUID().toString(), staffId = UUID.randomUUID().toString();
        String username = "protected" + ownerId.replace("-", "");
        inTenant("kebab", () -> {
            em.createNativeQuery("INSERT INTO users (id, username, password, active, tenant_id) VALUES (:id, :name, 'hash', TRUE, 'kebab')")
                    .setParameter("id", staffId).setParameter("name", "staff" + staffId).executeUpdate();
            return null;
        });
        byte[] zip = export("kebab");
        inTenant("default", () -> {
            em.createNativeQuery("INSERT INTO users (id, username, password, active, tenant_id) VALUES (:id, :name, 'protected-hash', TRUE, 'kebab')")
                    .setParameter("id", ownerId).setParameter("name", username).executeUpdate();
            em.createNativeQuery("INSERT INTO user_roles (user_id, role) VALUES (:id, 'OWNER')").setParameter("id", ownerId).executeUpdate();
            return null;
        });
        try {
            backups.importRestore("kebab", new ByteArrayInputStream(zip));
            assertFalse(json(export("kebab")).contains(username));
            backups.cleanTenantData("kebab");
            inTenant("default", () -> {
                assertEquals("protected-hash", em.createNativeQuery("SELECT password FROM users WHERE id = :id").setParameter("id", ownerId).getSingleResult());
                assertEquals("OWNER", em.createNativeQuery("SELECT role FROM user_roles WHERE user_id = :id").setParameter("id", ownerId).getSingleResult());
                assertEquals(0L, ((Number) em.createNativeQuery("SELECT count(*) FROM users WHERE id = :id").setParameter("id", staffId).getSingleResult()).longValue());
                return null;
            });
        } finally {
            inTenant("default", () -> { em.createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id", ownerId).executeUpdate(); return null; });
        }
    }
}
