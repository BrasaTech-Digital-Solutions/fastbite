package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.platform.PendingSandboxDeletionPort;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.statement;
import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.transaction;
import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.update;

@Component
public class PendingSandboxDeletionJdbcAdapter implements PendingSandboxDeletionPort {

    private final DataSource source;

    public PendingSandboxDeletionJdbcAdapter(DataSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public void enqueue(String tenantId, String ownerUsername, String lastError, Instant now, Instant nextAttemptAt) {
        String clipped = clip(lastError);
        transaction(source, c -> {
            int updated = update(c,
                    """
                    UPDATE public.pending_sandbox_deletions
                    SET owner_username = ?, last_error = ?, next_attempt_at = ?, updated_at = ?
                    WHERE tenant_id = ?
                    """,
                    ownerUsername, clipped, Timestamp.from(nextAttemptAt), Timestamp.from(now), tenantId);
            if (updated == 0) {
                update(c,
                        """
                        INSERT INTO public.pending_sandbox_deletions
                            (tenant_id, owner_username, attempts, next_attempt_at, last_error, created_at, updated_at)
                        VALUES (?, ?, 1, ?, ?, ?, ?)
                        """,
                        tenantId, ownerUsername, Timestamp.from(nextAttemptAt), clipped,
                        Timestamp.from(now), Timestamp.from(now));
            }
            return null;
        });
    }

    @Override
    public List<PendingDeletion> due(Instant now, int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        return transaction(source, c -> {
            List<PendingDeletion> rows = new ArrayList<>();
            try (var q = statement(c,
                    """
                    SELECT tenant_id, owner_username, attempts, next_attempt_at, last_error
                    FROM public.pending_sandbox_deletions
                    WHERE next_attempt_at <= ?
                    ORDER BY next_attempt_at ASC
                    LIMIT ?
                    """,
                    Timestamp.from(now), capped);
                 var r = q.executeQuery()) {
                while (r.next()) {
                    rows.add(new PendingDeletion(
                            r.getString("tenant_id"),
                            r.getString("owner_username"),
                            r.getInt("attempts"),
                            r.getTimestamp("next_attempt_at").toInstant(),
                            r.getString("last_error")));
                }
            }
            return rows;
        });
    }

    @Override
    public void markDone(String tenantId) {
        transaction(source, c -> {
            update(c, "DELETE FROM public.pending_sandbox_deletions WHERE tenant_id = ?", tenantId);
            return null;
        });
    }

    @Override
    public void reschedule(String tenantId, int attempts, Instant nextAttemptAt, String lastError, Instant now) {
        transaction(source, c -> {
            update(c,
                    """
                    UPDATE public.pending_sandbox_deletions
                    SET attempts = ?, next_attempt_at = ?, last_error = ?, updated_at = ?
                    WHERE tenant_id = ?
                    """,
                    attempts, Timestamp.from(nextAttemptAt), clip(lastError), Timestamp.from(now), tenantId);
            return null;
        });
    }

    private static String clip(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
