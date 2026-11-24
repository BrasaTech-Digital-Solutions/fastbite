package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.TenantDeletionPort;
import es.brasatech.fastbite.application.tenant.TenantRegistrationRules;
import es.brasatech.fastbite.jpa.legal.GuestPrivacyNoticeServiceJpaImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.statement;
import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.transaction;
import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.update;

@Component
public class TenantDeletionJdbcAdapter implements TenantDeletionPort {

    private static final Logger log = LoggerFactory.getLogger(TenantDeletionJdbcAdapter.class);

    private static final List<String> PLATFORM_TENANT_TABLES = List.of(
            "tenant_locations",
            "tenant_billing",
            "tenant_lifecycle",
            "owner_onboarding",
            "location_appearance",
            "sandbox_provisioning",
            "location_promotions",
            "location_promotion_lease",
            "fiscal_issuers"
    );

    private final DataSource source;
    private final String uploadDirectory;

    public TenantDeletionJdbcAdapter(
            DataSource source,
            @Value("${image.upload.directory:uploads/images}") String uploadDirectory) {
        this.source = Objects.requireNonNull(source, "source");
        this.uploadDirectory = Objects.requireNonNull(uploadDirectory, "uploadDirectory");
    }

    @Override
    public boolean hasFiscalOrPaidActivity(String tenantId) {
        String cleanTenant = TenantRegistrationRules.tenantId(tenantId);
        if (isProtectedTenant(cleanTenant)) {
            return true;
        }
        String schema = "tenant_" + cleanTenant;
        return transaction(source, c -> {
            if (tableHasAnyRow(c, schema, "fiscal_tickets", null)) {
                return true;
            }
            if (tableHasAnyRow(c, schema, "verifactu_records", null)) {
                return true;
            }
            // OrderPaymentStatus ordinals: 0=UNPAID, 1=PAID, 2=REFUNDED, 3=NEEDS_REFUND
            return tableHasAnyRow(c, schema, "orders", "payment_status IN (1, 2, 3)");
        });
    }

    @Override
    public void deleteTenantCompletely(String tenantId) {
        String cleanTenant = TenantRegistrationRules.tenantId(tenantId);
        if (isProtectedTenant(cleanTenant)) {
            throw new IllegalArgumentException("Protected tenant cannot be deleted: " + cleanTenant);
        }
        String schema = "tenant_" + cleanTenant;
        transaction(source, c -> {
            for (String table : PLATFORM_TENANT_TABLES) {
                if (tableExists(c, "public", table)) {
                    update(c, "DELETE FROM public." + table + " WHERE tenant_id = ?", cleanTenant);
                }
            }
            update(c, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            return null;
        });

        deleteDirectoryQuietly(resolveTenantMediaPath(cleanTenant), "media", cleanTenant);
        deleteDirectoryQuietly(
                GuestPrivacyNoticeServiceJpaImpl.resolveTenantDocumentsPath(uploadDirectory, cleanTenant),
                "documents",
                cleanTenant);
    }

    private static boolean isProtectedTenant(String cleanTenant) {
        return "kebab".equals(cleanTenant) || "default".equals(cleanTenant) || "public".equals(cleanTenant);
    }

    private static boolean tableExists(Connection c, String schema, String table) throws SQLException {
        try (var q = statement(c,
                "SELECT 1 FROM information_schema.tables WHERE LOWER(table_schema) = LOWER(?) AND LOWER(table_name) = LOWER(?)",
                schema, table);
             var r = q.executeQuery()) {
            return r.next();
        }
    }

    private static boolean tableHasAnyRow(Connection c, String schema, String table, String whereClause) throws SQLException {
        if (!tableExists(c, schema, table)) {
            return false;
        }
        String sql = "SELECT 1 FROM " + schema + "." + table
                + (whereClause != null && !whereClause.isBlank() ? " WHERE " + whereClause : "")
                + " LIMIT 1";
        try (var q = statement(c, sql);
             var r = q.executeQuery()) {
            return r.next();
        }
    }

    private Path resolveTenantMediaPath(String cleanTenant) {
        Path root = Paths.get(uploadDirectory).toAbsolutePath().normalize();
        Path tenantPath = root.resolve(cleanTenant).normalize();
        if (!tenantPath.startsWith(root) || tenantPath.equals(root)) {
            throw new IllegalArgumentException("Invalid tenant media path for " + cleanTenant);
        }
        return tenantPath;
    }

    private static void deleteDirectoryQuietly(Path dir, String kind, String tenantId) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        if (Files.isSymbolicLink(dir)) {
            log.warn("Refusing to delete symbolic link {} directory for tenant {}: {}", kind, tenantId, dir);
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Could not remove {} directory for deleted tenant {}: {}", kind, tenantId, e.getMessage());
        }
    }
}
