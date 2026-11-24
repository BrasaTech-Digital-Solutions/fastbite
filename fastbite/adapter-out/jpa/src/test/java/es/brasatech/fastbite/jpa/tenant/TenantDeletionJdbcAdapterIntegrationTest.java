package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.TenantDeletionPort;
import es.brasatech.fastbite.jpa.TestConfig;
import es.brasatech.fastbite.jpa.legal.GuestPrivacyNoticeServiceJpaImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = TestConfig.class,
        properties = "image.upload.directory=target/tenant-deletion-media"
)
class TenantDeletionJdbcAdapterIntegrationTest {

    @Autowired
    TenantDeletionPort deletionPort;

    @Autowired
    TenantMigrationService migrations;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${image.upload.directory}")
    String uploadDirectory;

    @Test
    void deletesSchemaPlatformRowsAndStorageWithoutTouchingOtherTenants() throws Exception {
        String target = "delclean";
        String keeper = "delkeep";
        migrations.provision(target);
        migrations.provision(keeper);

        registerPlatformRows(target, "ana");
        registerPlatformRows(keeper, "ana");

        Path targetMedia = Paths.get(uploadDirectory).toAbsolutePath().normalize().resolve(target);
        Path keeperMedia = Paths.get(uploadDirectory).toAbsolutePath().normalize().resolve(keeper);
        Path targetDocs = GuestPrivacyNoticeServiceJpaImpl.resolveTenantDocumentsPath(uploadDirectory, target);
        Path keeperDocs = GuestPrivacyNoticeServiceJpaImpl.resolveTenantDocumentsPath(uploadDirectory, keeper);
        Files.createDirectories(targetMedia);
        Files.createDirectories(keeperMedia);
        Files.createDirectories(targetDocs);
        Files.createDirectories(keeperDocs);
        Files.writeString(targetMedia.resolve("burger.jpg"), "img");
        Files.writeString(keeperMedia.resolve("pizza.jpg"), "img");
        Files.writeString(targetDocs.resolve("privacy.pdf"), "pdf");
        Files.writeString(keeperDocs.resolve("privacy.pdf"), "pdf");

        // Unpaid order alone does not count as paid activity
        jdbc.update("INSERT INTO tenant_" + target + ".orders (id, order_number, created_at, status, payment_status, total) VALUES (?, 1, CURRENT_TIMESTAMP, 0, 0, 10.00)",
                UUID.randomUUID().toString());
        assertThat(deletionPort.hasFiscalOrPaidActivity(target)).isFalse();

        deletionPort.deleteTenantCompletely(target);

        assertThat(schemaExists("tenant_" + target)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_locations WHERE tenant_id = ?", Integer.class, target)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_billing WHERE tenant_id = ?", Integer.class, target)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_lifecycle WHERE tenant_id = ?", Integer.class, target)).isZero();
        assertThat(Files.exists(targetMedia)).isFalse();
        assertThat(Files.exists(targetDocs)).isFalse();

        // Keeper tenant and its files remain completely untouched
        assertThat(schemaExists("tenant_" + keeper)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_locations WHERE tenant_id = ?", Integer.class, keeper)).isEqualTo(1);
        assertThat(Files.exists(keeperMedia.resolve("pizza.jpg"))).isTrue();
        assertThat(Files.exists(keeperDocs.resolve("privacy.pdf"))).isTrue();

        // Cleanup keeper
        deletionPort.deleteTenantCompletely(keeper);
    }

    @Test
    void detectsFiscalTicketsVerifactuRecordsAndPaidOrders() {
        String paidTenant = "delpaid";
        migrations.provision(paidTenant);
        assertThat(deletionPort.hasFiscalOrPaidActivity(paidTenant)).isFalse();
        jdbc.update("INSERT INTO tenant_" + paidTenant + ".orders (id, order_number, created_at, status, payment_status, total) VALUES (?, 1, CURRENT_TIMESTAMP, 4, 1, 15.50)",
                UUID.randomUUID().toString());
        assertThat(deletionPort.hasFiscalOrPaidActivity(paidTenant)).isTrue();
        deletionPort.deleteTenantCompletely(paidTenant);

        String ticketTenant = "delticket";
        migrations.provision(ticketTenant);
        assertThat(deletionPort.hasFiscalOrPaidActivity(ticketTenant)).isFalse();
        jdbc.update("""
                INSERT INTO tenant_delticket.fiscal_tickets
                (id, kind, series, ticket_number, full_number, training, issued_local, issued_offset,
                 lines, vat_lines, discount, total, vat_total, tip, payment_method)
                VALUES (?, 'SIMPLIFIED', 'DEL-T26', 1, 'DEL-T26-000001', FALSE, CURRENT_TIMESTAMP, '+01:00',
                 '[]', '[]', 0, 10.00, 0.91, 0, 'CARD')
                """, UUID.randomUUID().toString());
        assertThat(deletionPort.hasFiscalOrPaidActivity(ticketTenant)).isTrue();
        deletionPort.deleteTenantCompletely(ticketTenant);

        assertThatThrownBy(() -> deletionPort.deleteTenantCompletely("kebab"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void registerPlatformRows(String tenantId, String owner) {
        jdbc.update("INSERT INTO public.tenant_locations (id, owner_username, tenant_id, plan) VALUES (?, ?, ?, 'RESTAURANT')",
                UUID.randomUUID().toString(), owner, tenantId);
        jdbc.update("INSERT INTO public.tenant_billing (tenant_id, billing_key, status) VALUES (?, ?, 'LOCAL_TRIAL')",
                tenantId, UUID.randomUUID().toString());
        jdbc.update("INSERT INTO public.tenant_lifecycle (tenant_id, operation_id, owner_username, state, updated_at) VALUES (?, ?, ?, 'ACTIVE', 1)",
                tenantId, "test:" + tenantId, owner);
    }

    private boolean schemaExists(String schemaName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE LOWER(schema_name) = LOWER(?)",
                Integer.class, schemaName);
        return count != null && count > 0;
    }
}
