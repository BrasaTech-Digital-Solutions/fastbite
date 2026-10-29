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
            String sql = "SELECT id, subject_id, attempts, purpose FROM public.account_email_outbox WHERE purpose IN ('OWNER_SETUP', 'SIGNUP_VERIFY', 'SIGNUP_NOTICE') AND "
                    + (subjectId == null ? "" : "subject_id = ? AND ")
                    + "((state = 'PENDING' AND available_at <= ?) OR (state = 'CLAIMED' AND claimed_at <= ?)) ORDER BY created_at FETCH FIRST 1 ROWS ONLY FOR UPDATE SKIP LOCKED";
            Object[] args = subjectId == null ? new Object[]{now.toEpochMilli(), now.toEpochMilli() - LEASE_MS}
                    : new Object[]{subjectId, now.toEpochMilli(), now.toEpochMilli() - LEASE_MS};
            try (var query = statement(connection, sql, args); var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                String id = rows.getString(1), subject = rows.getString(2), claimId = UUID.randomUUID().toString();
                int attempts = rows.getInt(3) + 1;
                String purpose = rows.getString(4);
                if (attempts > MAX_ATTEMPTS) {
                    update(connection, "UPDATE public.account_email_outbox SET state = 'FAILED', claim_id = NULL, claimed_at = NULL WHERE id = ?", id);
                    log.warn("Account email delivery failed: outbox={} purpose={} attempt={} reason=lease-expired state=FAILED", id, purpose, attempts - 1);
                    return Optional.empty();
                }
                update(connection, "UPDATE public.account_email_outbox SET state = 'CLAIMED', claim_id = ?, claimed_at = ?, attempts = ? WHERE id = ?", claimId, now.toEpochMilli(), attempts, id);
                return Optional.of(new Claim(id, claimId, subject, attempts, purpose));
            }
        });
    }

    @Override public Optional<Recipient> issue(Claim claim, String hash, Instant now) {
        return transaction(connection -> {
            // Signup changes lock the reservation first; match their order to avoid a deadlock.
            if (!"OWNER_SETUP".equals(claim.purpose())) {
                try (var query = statement(connection, "SELECT id FROM public.signup_reservation_lock WHERE id = 1 FOR UPDATE"); var row = query.executeQuery()) {
                    if (!row.next()) throw new IllegalStateException("Signup lock is missing");
                }
            }
            String destination, language;
            try (var lock = statement(connection, "SELECT recipient_email, language FROM public.account_email_outbox WHERE id = ? AND state = 'CLAIMED' AND claim_id = ? AND purpose = ? FOR UPDATE", claim.id(), claim.claimId(), claim.purpose()); var row = lock.executeQuery()) {
                if (!row.next()) return Optional.empty();
                destination = row.getString(1); language = row.getString(2);
            }
            String email, username, limitKey;
            if ("OWNER_SETUP".equals(claim.purpose())) {
                try (var query = statement(connection, "SELECT t.email, t.username, t.completed, COALESCE(u.account_language, 'en') FROM public.owner_setup_tokens t JOIN public.users u ON u.id = t.user_id WHERE t.checkout_id = ? FOR UPDATE", claim.subjectId()); var row = query.executeQuery()) {
                    if (!row.next() || row.getBoolean(3)) return cancel(connection, claim);
                    email = row.getString(1); username = row.getString(2); language = row.getString(4);
                }
                limitKey = claim.subjectId();
            } else if ("SIGNUP_VERIFY".equals(claim.purpose())) {
                try (var query = statement(connection, "SELECT email, username, language, expires_at FROM public.pending_registrations WHERE id = ? FOR UPDATE", claim.subjectId()); var row = query.executeQuery()) {
                    if (!row.next() || row.getLong(4) <= now.toEpochMilli() || !row.getString(1).equals(destination)) return cancel(connection, claim);
                    email = row.getString(1); username = row.getString(2); language = row.getString(3);
                }
                limitKey = "signup:" + email;
            } else if ("SIGNUP_NOTICE".equals(claim.purpose())) {
                try (var query = statement(connection, "SELECT username, COALESCE(account_language, 'en') FROM public.users WHERE email = ? AND email_verified_at IS NOT NULL FOR UPDATE", destination); var row = query.executeQuery()) {
                    if (!row.next()) return cancel(connection, claim);
                    username = row.getString(1); language = row.getString(2); email = destination;
                }
                limitKey = "signup:" + email;
            } else return cancel(connection, claim);
            long time = now.toEpochMilli(), window = time - Duration.ofDays(1).toMillis();
            try (var query = statement(connection, "SELECT COUNT(*), MAX(sent_at), MIN(sent_at) FROM public.account_email_limits WHERE subject_key = ? AND purpose = ? AND sent_at > ?", limitKey, claim.purpose(), window); var row = query.executeQuery()) {
                row.next(); int count = row.getInt(1); long latest = row.getLong(2), first = row.getLong(3);
                if (count >= 5 || (count > 0 && latest + 60000 > time)) {
                    long next = Math.max(count >= 5 ? first + Duration.ofDays(1).toMillis() + 1 : 0, latest + 60000);
                    update(connection, "UPDATE public.account_email_outbox SET state = 'PENDING', claim_id = NULL, claimed_at = NULL, attempts = attempts - 1, available_at = ? WHERE id = ? AND claim_id = ?", next, claim.id(), claim.claimId());
                    return Optional.empty();
                }
            }
            if ("OWNER_SETUP".equals(claim.purpose())) {
                update(connection, "UPDATE public.owner_setup_tokens SET token_hash = ?, expires_at = ? WHERE checkout_id = ? AND completed = FALSE", hash, now.plus(Duration.ofHours(24)).toEpochMilli(), claim.subjectId());
            } else if ("SIGNUP_VERIFY".equals(claim.purpose())) {
                update(connection, "DELETE FROM public.account_tokens WHERE purpose = 'SIGNUP_VERIFY' AND subject_type = 'PENDING' AND subject_id = ?", claim.subjectId());
                update(connection, "INSERT INTO public.account_tokens (token_hash, purpose, subject_type, subject_id, email, created_at, expires_at) VALUES (?, 'SIGNUP_VERIFY', 'PENDING', ?, ?, ?, ?)", hash, claim.subjectId(), email, time, now.plus(Duration.ofHours(48)).toEpochMilli());
            }
            update(connection, "INSERT INTO public.account_email_limits (id, subject_key, purpose, sent_at) VALUES (?, ?, ?, ?)", UUID.randomUUID().toString(), limitKey, claim.purpose(), time);
            return Optional.of(new Recipient(email, username, language));
        });
    }
    private static Optional<Recipient> cancel(Connection connection, Claim claim) throws SQLException {
        update(connection, "UPDATE public.account_email_outbox SET state = 'CANCELLED', claim_id = NULL, claimed_at = NULL WHERE id = ? AND claim_id = ?", claim.id(), claim.claimId());
        return Optional.empty();
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
