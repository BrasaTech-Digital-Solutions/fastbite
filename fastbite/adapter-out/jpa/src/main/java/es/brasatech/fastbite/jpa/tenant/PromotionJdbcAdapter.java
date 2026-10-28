package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.platform.PromotionPort;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

@Component
public class PromotionJdbcAdapter implements PromotionPort {

    private static final String SELECT_FIELDS =
            "id, tenant_id, owner_username, state, token_hash, bundle_path, snapshot_path, "
            + "snapshot_name, summary, created_at, expires_at, applied_at, undone_at ";

    private final DataSource dataSource;

    public PromotionJdbcAdapter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public OptionalLong claimLease(String tenantId, Instant now, Duration leaseDuration) {
        long nowMs = now.toEpochMilli();
        long expiredBefore = now.minus(leaseDuration).toEpochMilli();

        // 1. ensureLeaseRow in its own transaction: ignore duplicate key violation if other request created it
        try {
            transaction(connection -> {
                update(connection,
                        "INSERT INTO public.location_promotion_lease (tenant_id, claimed_at) "
                                + "SELECT ?, NULL WHERE NOT EXISTS (SELECT 1 FROM public.location_promotion_lease WHERE tenant_id = ?)",
                        tenantId, tenantId);
                return null;
            });
        } catch (Exception ignored) {
            // Another thread/process created the row concurrently; continue to claim
        }

        // 2. Claim in its own transaction
        return transaction(connection -> {
            int updated = update(connection,
                    "UPDATE public.location_promotion_lease SET claimed_at = ? "
                            + "WHERE tenant_id = ? AND (claimed_at IS NULL OR claimed_at < ?)",
                    nowMs, tenantId, expiredBefore);
            return updated == 1 ? OptionalLong.of(nowMs) : OptionalLong.empty();
        });
    }

    @Override
    public void releaseLease(String tenantId, long claimedAt) {
        transaction(connection -> {
            update(connection,
                    "UPDATE public.location_promotion_lease SET claimed_at = NULL WHERE tenant_id = ? AND claimed_at = ?",
                    tenantId, claimedAt);
            return null;
        });
    }

    @Override
    public List<String> expireOtherPreviews(String tenantId) {
        return transaction(connection -> {
            List<String> bundlePaths = new ArrayList<>();
            try (var query = statement(connection,
                    "SELECT bundle_path FROM public.location_promotions WHERE tenant_id = ? AND state = ?",
                    tenantId, State.PREVIEWED.name());
                 var rows = query.executeQuery()) {
                while (rows.next()) {
                    String bp = rows.getString(1);
                    if (bp != null) bundlePaths.add(bp);
                }
            }
            update(connection,
                    "UPDATE public.location_promotions SET state = ? WHERE tenant_id = ? AND state = ?",
                    State.EXPIRED.name(), tenantId, State.PREVIEWED.name());
            return bundlePaths;
        });
    }

    @Override
    public void savePreview(Entry entry) {
        transaction(connection -> {
            update(connection,
                    "INSERT INTO public.location_promotions (id, tenant_id, owner_username, state, "
                            + "token_hash, bundle_path, snapshot_path, snapshot_name, summary, created_at, expires_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    entry.id(), entry.tenantId(), entry.ownerUsername(), entry.state().name(),
                    entry.tokenHash(), entry.bundlePath(), entry.snapshotPath(), entry.snapshotName(),
                    entry.summary(), entry.createdAt().toEpochMilli(), entry.expiresAt().toEpochMilli());
            return null;
        });
    }

    @Override
    public Optional<Entry> findByTokenHash(String tokenHash) {
        return transaction(connection -> {
            try (var query = statement(connection,
                    "SELECT " + SELECT_FIELDS + "FROM public.location_promotions WHERE token_hash = ?", tokenHash);
                 var rows = query.executeQuery()) {
                return rows.next() ? Optional.of(mapEntry(rows)) : Optional.empty();
            }
        });
    }

    @Override
    public Optional<Entry> findById(String id) {
        return transaction(connection -> {
            try (var query = statement(connection,
                    "SELECT " + SELECT_FIELDS + "FROM public.location_promotions WHERE id = ?", id);
                 var rows = query.executeQuery()) {
                return rows.next() ? Optional.of(mapEntry(rows)) : Optional.empty();
            }
        });
    }

    @Override
    public boolean consumeToken(String tokenHash, String tenantId, String ownerUsername, Instant now) {
        return transaction(connection -> {
            int updated = update(connection,
                    "UPDATE public.location_promotions SET state = ? "
                            + "WHERE token_hash = ? AND tenant_id = ? AND owner_username = ? AND state = ? AND expires_at > ?",
                    State.APPLYING.name(), tokenHash, tenantId, ownerUsername, State.PREVIEWED.name(), now.toEpochMilli());
            return updated > 0;
        });
    }

    @Override
    public void markApplied(String id, String snapshotPath, String snapshotName, Instant appliedAt) {
        transaction(connection -> {
            update(connection,
                    "UPDATE public.location_promotions SET state = ?, snapshot_path = ?, snapshot_name = ?, applied_at = ? WHERE id = ?",
                    State.APPLIED.name(), snapshotPath, snapshotName, appliedAt.toEpochMilli(), id);
            return null;
        });
    }

    @Override
    public void markUndone(String id, Instant undoneAt) {
        transaction(connection -> {
            update(connection,
                    "UPDATE public.location_promotions SET state = ?, undone_at = ? WHERE id = ?",
                    State.UNDONE.name(), undoneAt.toEpochMilli(), id);
            return null;
        });
    }

    @Override
    public void markFailed(String id) {
        transaction(connection -> {
            update(connection,
                    "UPDATE public.location_promotions SET state = ? WHERE id = ?",
                    State.FAILED.name(), id);
            return null;
        });
    }

    @Override
    public List<String> sweep(Instant now, Duration leaseDuration) {
        long nowMs = now.toEpochMilli();
        long activeLeaseThresholdMs = now.minus(leaseDuration).toEpochMilli();
        return transaction(connection -> {
            List<String> filesToDelete = new ArrayList<>();
            // Expire previewed past expires_at
            try (var query = statement(connection,
                    "SELECT bundle_path FROM public.location_promotions WHERE state = ? AND expires_at < ?",
                    State.PREVIEWED.name(), nowMs);
                 var rows = query.executeQuery()) {
                while (rows.next()) {
                    String bp = rows.getString(1);
                    if (bp != null) filesToDelete.add(bp);
                }
            }
            update(connection,
                    "UPDATE public.location_promotions SET state = ? WHERE state = ? AND expires_at < ?",
                    State.EXPIRED.name(), State.PREVIEWED.name(), nowMs);

            // Fail applying rows whose tenant has no live lease
            String notLiveLeaseCondition = "state = ? AND NOT EXISTS ("
                    + "SELECT 1 FROM public.location_promotion_lease l "
                    + "WHERE l.tenant_id = location_promotions.tenant_id "
                    + "AND l.claimed_at IS NOT NULL AND l.claimed_at >= ?)";

            try (var query = statement(connection,
                    "SELECT bundle_path, snapshot_path FROM public.location_promotions WHERE " + notLiveLeaseCondition,
                    State.APPLYING.name(), activeLeaseThresholdMs);
                 var rows = query.executeQuery()) {
                while (rows.next()) {
                    String bp = rows.getString(1);
                    if (bp != null) filesToDelete.add(bp);
                    String sp = rows.getString(2);
                    if (sp != null) filesToDelete.add(sp);
                }
            }
            update(connection,
                    "UPDATE public.location_promotions SET state = ? WHERE " + notLiveLeaseCondition,
                    State.FAILED.name(), State.APPLYING.name(), activeLeaseThresholdMs);

            return filesToDelete;
        });
    }

    @Override
    public Optional<Entry> findLatestAppliedOrUndone(String tenantId) {
        return transaction(connection -> {
            try (var query = statement(connection,
                    "SELECT " + SELECT_FIELDS + "FROM public.location_promotions "
                            + "WHERE tenant_id = ? AND state IN (?, ?) "
                            + "ORDER BY applied_at DESC LIMIT 1",
                    tenantId, State.APPLIED.name(), State.UNDONE.name());
                 var rows = query.executeQuery()) {
                return rows.next() ? Optional.of(mapEntry(rows)) : Optional.empty();
            }
        });
    }

    @Override
    public List<Entry> findAppliedOrUndoneBeyond(String tenantId, int keepCount) {
        return transaction(connection -> {
            List<Entry> all = new ArrayList<>();
            try (var query = statement(connection,
                    "SELECT " + SELECT_FIELDS + "FROM public.location_promotions "
                            + "WHERE tenant_id = ? AND state IN (?, ?) "
                            + "ORDER BY applied_at DESC",
                    tenantId, State.APPLIED.name(), State.UNDONE.name());
                 var rows = query.executeQuery()) {
                while (rows.next()) {
                    all.add(mapEntry(rows));
                }
            }
            if (all.size() <= keepCount) {
                return List.of();
            }
            return all.subList(keepCount, all.size());
        });
    }

    @Override
    public void deleteRow(String id) {
        transaction(connection -> {
            update(connection, "DELETE FROM public.location_promotions WHERE id = ?", id);
            return null;
        });
    }

    private static Entry mapEntry(ResultSet rows) throws SQLException {
        return new Entry(
                rows.getString(1),
                rows.getString(2),
                rows.getString(3),
                State.valueOf(rows.getString(4)),
                rows.getString(5),
                rows.getString(6),
                rows.getString(7),
                rows.getString(8),
                rows.getString(9),
                Instant.ofEpochMilli(rows.getLong(10)),
                Instant.ofEpochMilli(rows.getLong(11)),
                rows.getObject(12) != null ? Instant.ofEpochMilli(rows.getLong(12)) : null,
                rows.getObject(13) != null ? Instant.ofEpochMilli(rows.getLong(13)) : null
        );
    }

    private static PreparedStatement statement(Connection connection, String sql, Object... values) throws SQLException {
        var stmt = connection.prepareStatement(sql);
        for (int i = 0; i < values.length; i++) {
            stmt.setObject(i + 1, values[i]);
        }
        return stmt;
    }

    private static int update(Connection connection, String sql, Object... values) throws SQLException {
        try (var stmt = statement(connection, sql, values)) {
            return stmt.executeUpdate();
        }
    }

    private <T> T transaction(Work<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error during promotion operation", e);
        }
    }

    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }
}
