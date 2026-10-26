package es.brasatech.fastbite.jpa.fiscal;

import es.brasatech.fastbite.application.fiscal.FiscalIssuerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class FiscalIssuerJdbcAdapter implements FiscalIssuerPort {

    private final JdbcTemplate jdbc;
    private final boolean isPostgres;

    public FiscalIssuerJdbcAdapter(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        boolean postgres = false;
        try (var conn = dataSource.getConnection()) {
            postgres = "PostgreSQL".equalsIgnoreCase(conn.getMetaData().getDatabaseProductName());
        } catch (SQLException ignored) {
        }
        this.isPostgres = postgres;
    }

    @Override
    public boolean recordIfAbsent(String taxId, String tenantId, Instant seenAt) {
        if (taxId == null || tenantId == null || seenAt == null) {
            return false;
        }
        Timestamp timestamp = Timestamp.from(seenAt);
        try {
            int rows;
            if (isPostgres) {
                rows = jdbc.update(
                        "INSERT INTO public.fiscal_issuers (tax_id, tenant_id, first_seen_at) VALUES (?, ?, ?) ON CONFLICT (tax_id, tenant_id) DO NOTHING",
                        taxId, tenantId, timestamp);
            } else {
                rows = jdbc.update(
                        "INSERT INTO public.fiscal_issuers (tax_id, tenant_id, first_seen_at) SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM public.fiscal_issuers WHERE tax_id = ? AND tenant_id = ?)",
                        taxId, tenantId, timestamp, taxId, tenantId);
            }
            return rows > 0;
        } catch (DataIntegrityViolationException e) {
            // Tolerate duplicate-key race: already recorded
            return false;
        }
    }

    @Override
    public Optional<Instant> findPriorUsageByOwner(String ownerUsername, String taxId, String currentTenantId) {
        if (ownerUsername == null || taxId == null) {
            return Optional.empty();
        }
        String sql = """
            SELECT MIN(fi.first_seen_at)
            FROM public.fiscal_issuers fi
            JOIN (
                SELECT tenant_id, owner_username FROM public.tenant_locations
                UNION
                SELECT tenant_id, owner_username FROM public.tenant_lifecycle WHERE state = 'ACTIVE'
            ) o ON fi.tenant_id = o.tenant_id
            WHERE o.owner_username = ? AND fi.tax_id = ? AND fi.tenant_id != ?
            """;
        List<Timestamp> results = jdbc.query(sql, (rs, rowNum) -> rs.getTimestamp(1), ownerUsername, taxId, currentTenantId != null ? currentTenantId : "");
        if (results.isEmpty() || results.get(0) == null) {
            return Optional.empty();
        }
        return Optional.of(results.get(0).toInstant());
    }
}
