package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.mail.AccountMailOutboxPort;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Claims and token issuance commit before SMTP begins; stale leases can be reclaimed. */
@Component
public class AccountMailOutboxJdbcAdapter implements AccountMailOutboxPort {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AccountMailOutboxJdbcAdapter.class);
    private static final long LEASE_MS = Duration.ofMinutes(2).toMillis();
    private static final int MAX_ATTEMPTS = 5;
    private final DataSource source;
    public AccountMailOutboxJdbcAdapter(DataSource source) { this.source = source; }

    @Override public Optional<Claim> claim(Instant now) { return claim(null, now); }
    @Override public Optional<Claim> claimForSubject(String subjectId, Instant now) { return claim(subjectId, now); }

    private Optional<Claim> claim(String subjectId, Instant now) {
        return transaction(connection -> {
            String sql = "SELECT id, subject_id, attempts FROM public.account_email_outbox WHERE purpose = 'OWNER_SETUP' AND "
                    + (subjectId == null ? "" : "subject_id = ? AND ")
                    + "((state = 'PENDING' AND available_at <= ?) OR (state = 'CLAIMED' AND claimed_at <= ?)) ORDER BY created_at FETCH FIRST 1 ROWS ONLY FOR UPDATE SKIP LOCKED";
            Object[] args = subjectId == null ? new Object[]{now.toEpochMilli(), now.toEpochMilli() - LEASE_MS}
                    : new Object[]{subjectId, now.toEpochMilli(), now.toEpochMilli() - LEASE_MS};
            try (var query = statement(connection, sql, args); var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                String id = rows.getString(1), subject = rows.getString(2), claimId = UUID.randomUUID().toString();
                int attempts = rows.getInt(3) + 1;
                if (attempts > MAX_ATTEMPTS) {
                    update(connection, "UPDATE public.account_email_outbox SET state = 'FAILED', claim_id = NULL, claimed_at = NULL WHERE id = ?", id);
                    log.warn("Account email delivery failed: outbox={} purpose=OWNER_SETUP attempt={} reason=lease-expired state=FAILED", id, attempts - 1);
                    return Optional.empty();
                }
                update(connection, "UPDATE public.account_email_outbox SET state = 'CLAIMED', claim_id = ?, claimed_at = ?, attempts = ? WHERE id = ?", claimId, now.toEpochMilli(), attempts, id);
                return Optional.of(new Claim(id, claimId, subject, attempts));
            }
        });
    }

    @Override public Optional<Recipient> issueOwnerSetup(Claim claim, String hash, Instant now) {
        return transaction(connection -> {
            try (var lock = statement(connection, "SELECT id FROM public.account_email_outbox WHERE id = ? AND state = 'CLAIMED' AND claim_id = ? FOR UPDATE", claim.id(), claim.claimId()); var row = lock.executeQuery()) {
                if (!row.next()) return Optional.empty();
            }
            String email, username, language;
            try (var query = statement(connection, "SELECT t.email, t.username, t.completed, COALESCE(u.account_language, 'en') FROM public.owner_setup_tokens t JOIN public.users u ON u.id = t.user_id WHERE t.checkout_id = ? FOR UPDATE", claim.subjectId()); var row = query.executeQuery()) {
                if (!row.next() || row.getBoolean(3)) {
                    update(connection, "UPDATE public.account_email_outbox SET state = 'CANCELLED' WHERE id = ? AND claim_id = ?", claim.id(), claim.claimId());
                    return Optional.empty();
                }
                email = row.getString(1); username = row.getString(2); language = row.getString(4);
            }
            long time = now.toEpochMilli(), window = time - Duration.ofDays(1).toMillis();
            try (var query = statement(connection, "SELECT COUNT(*), MAX(sent_at), MIN(sent_at) FROM public.account_email_limits WHERE subject_key = ? AND purpose = 'OWNER_SETUP' AND sent_at > ?", claim.subjectId(), window); var row = query.executeQuery()) {
                row.next(); int count = row.getInt(1); long latest = row.getLong(2), first = row.getLong(3);
                if (count >= 5 || (count > 0 && latest + 60000 > time)) {
                    long next = Math.max(count >= 5 ? first + Duration.ofDays(1).toMillis() + 1 : 0, latest + 60000);
                    update(connection, "UPDATE public.account_email_outbox SET state = 'PENDING', claim_id = NULL, claimed_at = NULL, attempts = attempts - 1, available_at = ? WHERE id = ? AND claim_id = ?", next, claim.id(), claim.claimId());
                    return Optional.empty();
                }
            }
            update(connection, "UPDATE public.owner_setup_tokens SET token_hash = ?, expires_at = ? WHERE checkout_id = ? AND completed = FALSE", hash, now.plus(Duration.ofHours(24)).toEpochMilli(), claim.subjectId());
            update(connection, "INSERT INTO public.account_email_limits (id, subject_key, purpose, sent_at) VALUES (?, ?, 'OWNER_SETUP', ?)", UUID.randomUUID().toString(), claim.subjectId(), time);
            return Optional.of(new Recipient(email, username, language));
        });
    }

    @Override public void sent(Claim claim, Instant now) {
        transaction(connection -> {
            update(connection, "UPDATE public.account_email_outbox SET state = 'SENT', sent_at = ?, claim_id = NULL, claimed_at = NULL WHERE id = ? AND state = 'CLAIMED' AND claim_id = ?", now.toEpochMilli(), claim.id(), claim.claimId());
            return null;
        });
    }
    @Override public String failed(Claim claim, Instant now) {
        return transaction(connection -> {
            long backoff = Duration.ofMinutes(1L << Math.min(claim.attempts() - 1, 5)).toMillis();
            String state = claim.attempts() >= MAX_ATTEMPTS ? "FAILED" : "PENDING";
            int updated = update(connection, "UPDATE public.account_email_outbox SET state = ?, available_at = ?, claim_id = NULL, claimed_at = NULL WHERE id = ? AND state = 'CLAIMED' AND claim_id = ?", state, now.toEpochMilli() + backoff, claim.id(), claim.claimId());
            return updated == 1 ? state : "STALE";
        });
    }
    private static PreparedStatement statement(Connection connection, String sql, Object... args) throws SQLException {
        var statement = connection.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
        return statement;
    }
    private static int update(Connection connection, String sql, Object... args) throws SQLException {
        try (var statement = statement(connection, sql, args)) { return statement.executeUpdate(); }
    }
    private <T> T transaction(Work<T> work) {
        try (var connection = source.getConnection()) {
            boolean original = connection.getAutoCommit(); connection.setAutoCommit(false);
            try { T result = work.run(connection); connection.commit(); return result; }
            catch (RuntimeException | SQLException error) { connection.rollback(); throw new IllegalStateException("Account email operation failed"); }
            finally { connection.setAutoCommit(original); }
        } catch (SQLException error) { throw new IllegalStateException("Account email database unavailable"); }
    }
    @FunctionalInterface private interface Work<T> { T run(Connection connection) throws SQLException; }
}
