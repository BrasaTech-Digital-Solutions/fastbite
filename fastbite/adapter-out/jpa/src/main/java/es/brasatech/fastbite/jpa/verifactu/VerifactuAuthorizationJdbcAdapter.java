package es.brasatech.fastbite.jpa.verifactu;

import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationStore;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorization;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorizationStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC persistence adapter for {@code public.verifactu_authorizations} and
 * {@code public.digital_sign_webhook_events}.
 */
@Component
public class VerifactuAuthorizationJdbcAdapter implements VerifactuAuthorizationStore {

    private static final String SELECT_COLUMNS = """
            id, nif, legal_name, status, digital_sign_request_id, idempotency_key, attempt, template_id,
            signer_name, certificate_nif,
            signed_pdf_path, signed_pdf_sha256, evidence_pdf_path, evidence_pdf_sha256,
            failure_reason, created_by, created_at, updated_at, valid_from, revoked_at, last_synced_at,
            version, sync_failure_count, sync_failure_since
            """;

    private static final RowMapper<VerifactuAuthorization> ROW_MAPPER = VerifactuAuthorizationJdbcAdapter::mapRow;

    private final JdbcTemplate jdbc;

    public VerifactuAuthorizationJdbcAdapter(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public VerifactuAuthorization insert(VerifactuAuthorization auth) {
        Objects.requireNonNull(auth, "auth");
        jdbc.update("""
                INSERT INTO public.verifactu_authorizations (
                    id, nif, legal_name, status, valid_nif, active_nif,
                    digital_sign_request_id, idempotency_key, attempt, template_id,
                    signer_name, certificate_nif,
                    signed_pdf_path, signed_pdf_sha256, evidence_pdf_path, evidence_pdf_sha256,
                    failure_reason, created_by, created_at, updated_at, valid_from, revoked_at, last_synced_at,
                    version, sync_failure_count, sync_failure_since
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                auth.id().toString(),
                auth.nif(),
                auth.legalName(),
                auth.status().name(),
                validNifColumn(auth),
                activeNifColumn(auth),
                auth.digitalSignRequestId(),
                auth.idempotencyKey(),
                auth.attempt(),
                auth.templateId(),
                auth.signerName(),
                auth.certificateNif(),
                auth.signedPdfPath(),
                auth.signedPdfSha256(),
                auth.evidencePdfPath(),
                auth.evidencePdfSha256(),
                auth.failureReason(),
                auth.createdBy(),
                toTimestamp(auth.createdAt()),
                toTimestamp(auth.updatedAt()),
                toTimestamp(auth.validFrom()),
                toTimestamp(auth.revokedAt()),
                toTimestamp(auth.lastSyncedAt()),
                auth.version(),
                auth.syncFailureCount(),
                toTimestamp(auth.syncFailureSince()));
        return auth;
    }

    @Override
    public VerifactuAuthorization update(VerifactuAuthorization auth) {
        return compareAndSet(auth).authorization();
    }

    @Override
    public UpdateResult compareAndSet(VerifactuAuthorization auth) {
        Objects.requireNonNull(auth, "auth");
        int nextVersion = auth.version() + 1;
        int updated = jdbc.update("""
                UPDATE public.verifactu_authorizations
                SET nif = ?,
                    legal_name = ?,
                    status = ?,
                    valid_nif = ?,
                    active_nif = ?,
                    digital_sign_request_id = ?,
                    template_id = ?,
                    signer_name = ?,
                    certificate_nif = ?,
                    signed_pdf_path = ?,
                    signed_pdf_sha256 = ?,
                    evidence_pdf_path = ?,
                    evidence_pdf_sha256 = ?,
                    failure_reason = ?,
                    updated_at = ?,
                    valid_from = ?,
                    revoked_at = ?,
                    last_synced_at = ?,
                    version = ?,
                    sync_failure_count = ?,
                    sync_failure_since = ?
                WHERE id = ? AND version = ?
                """,
                auth.nif(),
                auth.legalName(),
                auth.status().name(),
                validNifColumn(auth),
                activeNifColumn(auth),
                auth.digitalSignRequestId(),
                auth.templateId(),
                auth.signerName(),
                auth.certificateNif(),
                auth.signedPdfPath(),
                auth.signedPdfSha256(),
                auth.evidencePdfPath(),
                auth.evidencePdfSha256(),
                auth.failureReason(),
                toTimestamp(auth.updatedAt()),
                toTimestamp(auth.validFrom()),
                toTimestamp(auth.revokedAt()),
                toTimestamp(auth.lastSyncedAt()),
                nextVersion,
                auth.syncFailureCount(),
                toTimestamp(auth.syncFailureSince()),
                auth.id().toString(),
                auth.version());
        if (updated == 1) {
            return new UpdateResult(auth.withVersion(nextVersion), true);
        }
        return new UpdateResult(findById(auth.id()).orElse(auth), false);
    }

    @Override
    public Optional<VerifactuAuthorization> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        List<VerifactuAuthorization> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM public.verifactu_authorizations WHERE id = ?",
                ROW_MAPPER,
                id.toString());
        return rows.stream().findFirst();
    }

    @Override
    public Optional<VerifactuAuthorization> findByDigitalSignRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        List<VerifactuAuthorization> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM public.verifactu_authorizations WHERE digital_sign_request_id = ?",
                ROW_MAPPER,
                requestId.strip());
        return rows.stream().findFirst();
    }

    @Override
    public Optional<VerifactuAuthorization> findValidByNif(String nif) {
        if (nif == null || nif.isBlank()) {
            return Optional.empty();
        }
        List<VerifactuAuthorization> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM public.verifactu_authorizations WHERE valid_nif = ?",
                ROW_MAPPER,
                nif.strip());
        return rows.stream().findFirst();
    }

    @Override
    public Optional<VerifactuAuthorization> findActiveByNif(String nif) {
        if (nif == null || nif.isBlank()) {
            return Optional.empty();
        }
        List<VerifactuAuthorization> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM public.verifactu_authorizations WHERE active_nif = ?",
                ROW_MAPPER,
                nif.strip());
        return rows.stream().findFirst();
    }

    @Override
    public Optional<VerifactuAuthorization> findLatestByNif(String nif) {
        if (nif == null || nif.isBlank()) {
            return Optional.empty();
        }
        List<VerifactuAuthorization> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS
                        + " FROM public.verifactu_authorizations WHERE nif = ?"
                        + " ORDER BY created_at DESC, attempt DESC FETCH FIRST 1 ROWS ONLY",
                ROW_MAPPER,
                nif.strip());
        return rows.stream().findFirst();
    }

    @Override
    public int nextAttemptForNif(String nif) {
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt), 0) + 1 FROM public.verifactu_authorizations WHERE nif = ?",
                Integer.class,
                nif.strip());
        return next == null ? 1 : next;
    }

    @Override
    public boolean hasValidAuthorization(String nif) {
        if (nif == null || nif.isBlank()) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM public.verifactu_authorizations
                WHERE valid_nif = ?
                  AND status = 'VALID'
                  AND valid_from IS NOT NULL
                  AND revoked_at IS NULL
                  AND signed_pdf_path IS NOT NULL
                  AND signed_pdf_sha256 IS NOT NULL
                  AND evidence_pdf_path IS NOT NULL
                  AND evidence_pdf_sha256 IS NOT NULL
                """,
                Integer.class,
                nif.strip());
        return count != null && count > 0;
    }

    @Override
    public boolean wasAuthorizedAt(String nif, Instant issuedAt) {
        if (nif == null || nif.isBlank() || issuedAt == null) {
            return false;
        }
        Timestamp ts = Timestamp.from(issuedAt);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM public.verifactu_authorizations
                WHERE nif = ?
                  AND status IN ('VALID', 'REVOKED')
                  AND valid_from IS NOT NULL
                  AND valid_from <= ?
                  AND (revoked_at IS NULL OR ? < revoked_at)
                  AND signed_pdf_path IS NOT NULL
                  AND signed_pdf_sha256 IS NOT NULL
                  AND evidence_pdf_path IS NOT NULL
                  AND evidence_pdf_sha256 IS NOT NULL
                """,
                Integer.class,
                nif.strip(),
                ts,
                ts);
        return count != null && count > 0;
    }

    @Override
    public List<VerifactuAuthorization> findDueForReconciliation(Instant staleBefore, int limit) {
        Objects.requireNonNull(staleBefore, "staleBefore");
        int cappedLimit = Math.max(1, limit);
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS
                        + " FROM public.verifactu_authorizations"
                        + " WHERE status = 'DOWNLOAD_FAILED'"
                        + "    OR (status = 'IN_PROGRESS' AND COALESCE(last_synced_at, created_at) <= ?)"
                        + " ORDER BY COALESCE(last_synced_at, created_at) ASC"
                        + " FETCH FIRST " + cappedLimit + " ROWS ONLY",
                ROW_MAPPER,
                Timestamp.from(staleBefore));
    }

    @Override
    public boolean recordWebhookEventIfNew(String eventId, String eventType, String requestId, Instant receivedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(receivedAt, "receivedAt");
        try {
            jdbc.update("""
                    INSERT INTO public.digital_sign_webhook_events (
                        event_id, event_type, request_id, received_at
                    ) VALUES (?, ?, ?, ?)
                    """,
                    eventId,
                    eventType,
                    requestId,
                    Timestamp.from(receivedAt));
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            if (isUniqueViolation(duplicate)) {
                return false;
            }
            throw duplicate;
        }
    }

    @Override
    public void markWebhookEventProcessed(String eventId, Instant processedAt) {
        if (eventId == null || eventId.isBlank() || processedAt == null) {
            return;
        }
        jdbc.update(
                "UPDATE public.digital_sign_webhook_events SET processed_at = ? WHERE event_id = ?",
                Timestamp.from(processedAt),
                eventId);
    }

    @Override
    public void deleteWebhookEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        jdbc.update(
                "DELETE FROM public.digital_sign_webhook_events WHERE event_id = ?",
                eventId);
    }

    @Override
    public int purgeWebhookEventsOlderThan(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        return jdbc.update(
                "DELETE FROM public.digital_sign_webhook_events WHERE received_at < ?",
                Timestamp.from(cutoff));
    }

    private static VerifactuAuthorization mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new VerifactuAuthorization(
                UUID.fromString(rs.getString("id")),
                rs.getString("nif"),
                rs.getString("legal_name"),
                VerifactuAuthorizationStatus.valueOf(rs.getString("status")),
                rs.getString("digital_sign_request_id"),
                rs.getString("idempotency_key"),
                rs.getInt("attempt"),
                rs.getString("template_id"),
                rs.getString("signer_name"),
                rs.getString("certificate_nif"),
                rs.getString("signed_pdf_path"),
                rs.getString("signed_pdf_sha256"),
                rs.getString("evidence_pdf_path"),
                rs.getString("evidence_pdf_sha256"),
                rs.getString("failure_reason"),
                rs.getString("created_by"),
                fromTimestamp(rs.getTimestamp("created_at")),
                fromTimestamp(rs.getTimestamp("updated_at")),
                fromTimestamp(rs.getTimestamp("valid_from")),
                fromTimestamp(rs.getTimestamp("revoked_at")),
                fromTimestamp(rs.getTimestamp("last_synced_at")),
                rs.getInt("version"),
                rs.getInt("sync_failure_count"),
                fromTimestamp(rs.getTimestamp("sync_failure_since")));
    }

    private static String validNifColumn(VerifactuAuthorization auth) {
        return auth.status() == VerifactuAuthorizationStatus.VALID ? auth.nif() : null;
    }

    private static String activeNifColumn(VerifactuAuthorization auth) {
        return auth.status().isActive() ? auth.nif() : null;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant fromTimestamp(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static boolean isUniqueViolation(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
