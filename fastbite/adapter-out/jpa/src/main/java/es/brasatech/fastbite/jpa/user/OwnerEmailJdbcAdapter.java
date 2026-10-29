package es.brasatech.fastbite.jpa.user;

import es.brasatech.fastbite.application.office.OwnerEmailPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class OwnerEmailJdbcAdapter implements OwnerEmailPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public OwnerEmailJdbcAdapter(JdbcTemplate jdbc, TransactionTemplate transactions) { this.jdbc = jdbc; this.transactions = transactions; }
    @Override public Optional<Status> status(String username) {
        return jdbc.query("SELECT id, email, pending_email, email_verified_at FROM public.users WHERE username = ? AND active = TRUE",
                (r, n) -> new Status(r.getString(1), r.getString(2), r.getString(3), r.getObject(4) != null), username).stream().findFirst();
    }
    @Override public boolean request(String username, String email, String language, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            lock();
            var owner = locked(username);
            if (owner.isEmpty()) return false;
            var s = owner.get();
            jdbc.update("UPDATE public.users SET pending_email = ?, account_language = ? WHERE id = ?", email, language, s.id());
            jdbc.update("DELETE FROM public.account_tokens WHERE purpose = 'OWNER_VERIFY' AND subject_id = ? AND consumed_at IS NULL", s.id());
            enqueue(s.id(), email, language, "OWNER_VERIFY", now);
            return true;
        }));
    }
    @Override public boolean resend(String username, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            lock(); var owner = locked(username);
            if (owner.isEmpty() || owner.get().pendingEmail() == null) return false;
            var s = owner.get();
            String language = jdbc.queryForObject("SELECT COALESCE(account_language, 'en') FROM public.users WHERE id = ?", String.class, s.id());
            enqueue(s.id(), s.pendingEmail(), language, "OWNER_VERIFY", now); return true;
        }));
    }
    @Override public Optional<String> verify(String hash, Instant now) {
        return transactions.execute(tx -> {
            lock();
            var rows = jdbc.query("SELECT t.subject_id, t.email, t.consumed_at, t.expires_at, u.email, u.email_verified_at, u.pending_email, COALESCE(u.account_language, 'en') FROM public.account_tokens t JOIN public.users u ON u.id = t.subject_id WHERE t.token_hash = ? AND t.purpose = 'OWNER_VERIFY' AND t.subject_type = 'OWNER' FOR UPDATE", (r, n) -> new Object[]{r.getString(1), r.getString(2), r.getObject(3), r.getLong(4), r.getString(5), r.getObject(6), r.getString(7), r.getString(8)}, hash);
            if (rows.isEmpty()) return Optional.empty();
            Object[] r = rows.getFirst(); String id = (String) r[0], email = (String) r[1];
            if (r[2] != null) return email.equals(r[4]) && r[5] != null ? Optional.of(id) : Optional.empty();
            if ((long) r[3] <= now.toEpochMilli() || !email.equals(r[6])) return Optional.empty();
            Integer occupied = jdbc.queryForObject("SELECT COUNT(*) FROM public.users WHERE email = ? AND id <> ?", Integer.class, email, id);
            if (occupied != null && occupied > 0) return Optional.empty();
            Integer reserved = jdbc.queryForObject("SELECT COUNT(*) FROM public.pending_registrations WHERE email = ? AND expires_at > ?", Integer.class, email, now.toEpochMilli());
            if (reserved != null && reserved > 0) return Optional.empty();
            jdbc.update("UPDATE public.users SET email = ?, email_verified_at = ?, pending_email = NULL WHERE id = ?", email, now.toEpochMilli(), id);
            jdbc.update("UPDATE public.account_tokens SET consumed_at = ? WHERE token_hash = ?", now.toEpochMilli(), hash);
            if (r[4] != null && r[5] != null && !email.equals(r[4])) enqueue(id, (String) r[4], (String) r[7], "OWNER_EMAIL_NOTICE", now);
            return Optional.of(id);
        });
    }
    private void lock() { jdbc.queryForObject("SELECT id FROM public.signup_reservation_lock WHERE id = 1 FOR UPDATE", Integer.class); }
    private Optional<Status> locked(String username) {
        return jdbc.query("SELECT id, email, pending_email, email_verified_at FROM public.users WHERE username = ? AND active = TRUE FOR UPDATE", (r, n) -> new Status(r.getString(1), r.getString(2), r.getString(3), r.getObject(4) != null), username).stream().findFirst();
    }
    private void enqueue(String id, String email, String language, String purpose, Instant now) {
        jdbc.update("INSERT INTO public.account_email_outbox (id, purpose, subject_type, subject_id, language, recipient_email, state, attempts, available_at, created_at) VALUES (?, ?, 'OWNER', ?, ?, ?, 'PENDING', 0, ?, ?)", UUID.randomUUID().toString(), purpose, id, language, email, now.toEpochMilli(), now.toEpochMilli());
    }
}
