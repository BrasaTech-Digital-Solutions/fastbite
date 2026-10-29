package es.brasatech.fastbite.jpa.user;

import es.brasatech.fastbite.application.office.StaffAccountPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class StaffAccountJdbcAdapter implements StaffAccountPort {
    private final JdbcTemplate jdbc; private final TransactionTemplate tx;
    public StaffAccountJdbcAdapter(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc = jdbc; this.tx = tx; }
    @Override public List<Staff> list(String actor, boolean owner, String tenant) {
        return tx.execute(status -> { authorize(actor, owner, tenant); return query(tenant, null, owner, false); });
    }
    @Override public Optional<Staff> find(String actor, boolean owner, String tenant, String id) {
        return tx.execute(status -> { authorize(actor, owner, tenant); return query(tenant, id, owner, false).stream().findFirst(); });
    }
    @Override public Staff setPassword(String actor, boolean owner, String tenant, String id, String passwordHash) {
        return tx.execute(status -> {
            lock(); authorize(actor, owner, tenant); Staff target = require(tenant, id, owner);
            jdbc.update("UPDATE " + schema(tenant) + ".users SET password = ? WHERE id = ?", passwordHash, id);
            jdbc.update("DELETE FROM public.account_tokens WHERE subject_type = 'STAFF' AND subject_id = ? AND tenant_id = ? AND purpose = 'PASSWORD_RESET' AND consumed_at IS NULL", id, tenant);
            return target;
        });
    }
    @Override public Staff setEmail(String actor, boolean owner, String tenant, String id, String email, String language, Instant now) {
        return tx.execute(status -> {
            lock(); authorize(actor, owner, tenant); Staff target = require(tenant, id, owner);
            jdbc.update("UPDATE " + schema(tenant) + ".users SET pending_email = ?, account_language = ? WHERE id = ?", email, language, id);
            jdbc.update("DELETE FROM public.account_tokens WHERE subject_type = 'STAFF' AND subject_id = ? AND tenant_id = ? AND purpose = 'STAFF_VERIFY' AND consumed_at IS NULL", id, tenant);
            enqueue("STAFF_VERIFY", target, tenant, email, language, now); return target;
        });
    }
    @Override public Optional<Staff> enqueueReset(String actor, boolean owner, String tenant, String id, Instant now) {
        return tx.execute(status -> {
            lock(); authorize(actor, owner, tenant); Staff target = require(tenant, id, owner);
            if (!target.verified() || target.email() == null) return Optional.empty();
            String language = jdbc.queryForObject("SELECT COALESCE(account_language, 'en') FROM " + schema(tenant) + ".users WHERE id = ?", String.class, id);
            enqueue("PASSWORD_RESET", target, tenant, target.email(), language, now); return Optional.of(target);
        });
    }
    @Override public boolean verifyEmail(String hash, Instant now) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            lock();
            var tokens = jdbc.query("SELECT subject_id, tenant_id, email, consumed_at, expires_at FROM public.account_tokens WHERE token_hash = ? AND purpose = 'STAFF_VERIFY' AND subject_type = 'STAFF' FOR UPDATE", (r, n) -> new Object[]{r.getString(1), r.getString(2), r.getString(3), r.getObject(4), r.getLong(5)}, hash);
            if (tokens.isEmpty()) return false;
            Object[] token = tokens.getFirst(); String id = (String) token[0], tenant = (String) token[1], email = (String) token[2];
            if (!validTenant(tenant)) return false;
            var users = jdbc.query("SELECT email, email_verified_at, pending_email FROM " + schema(tenant) + ".users WHERE id = ? AND active = TRUE FOR UPDATE", (r, n) -> new Object[]{r.getString(1), r.getObject(2), r.getString(3)}, id);
            if (users.isEmpty()) return false;
            Object[] user = users.getFirst();
            if (token[3] != null) return email.equals(user[0]) && user[1] != null;
            if ((long) token[4] <= now.toEpochMilli() || !email.equals(user[2])) return false;
            jdbc.update("UPDATE " + schema(tenant) + ".users SET email = ?, email_verified_at = ?, pending_email = NULL WHERE id = ?", email, now.toEpochMilli(), id);
            jdbc.update("UPDATE public.account_tokens SET consumed_at = ? WHERE token_hash = ?", now.toEpochMilli(), hash); return true;
        }));
    }
    private void authorize(String actor, boolean owner, String tenant) {
        String schema = schema(tenant);
        Integer count = owner ? jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_locations WHERE tenant_id = ? AND owner_username = ?", Integer.class, tenant, actor)
                : jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".users u JOIN " + schema + ".user_roles r ON r.user_id = u.id WHERE u.username = ? AND u.active = TRUE AND r.role = 'MANAGER'", Integer.class, actor);
        if (count == null || count != 1) throw new SecurityException("Staff account access denied");
    }
    private List<Staff> query(String tenant, String id, boolean owner, boolean lock) {
        String schema = schema(tenant);
        String sql = "SELECT u.id, u.username, u.full_name, u.email, u.pending_email, u.email_verified_at FROM " + schema + ".users u WHERE u.active = TRUE AND NOT EXISTS (SELECT 1 FROM " + schema + ".user_roles r WHERE r.user_id = u.id AND r.role " + (owner ? "= 'OWNER'" : "IN ('OWNER', 'ADMIN', 'MANAGER')") + ")" + (id == null ? "" : " AND u.id = ?") + (lock ? " FOR UPDATE" : "");
        var mapper = (org.springframework.jdbc.core.RowMapper<Staff>) (r, n) -> new Staff(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getObject(6) != null);
        return id == null ? jdbc.query(sql, mapper) : jdbc.query(sql, mapper, id);
    }
    private Staff require(String tenant, String id, boolean owner) { return query(tenant, id, owner, true).stream().findFirst().orElseThrow(() -> new SecurityException("Staff account access denied")); }
    private void lock() { jdbc.queryForObject("SELECT id FROM public.signup_reservation_lock WHERE id = 1 FOR UPDATE", Integer.class); }
    private void enqueue(String purpose, Staff target, String tenant, String email, String language, Instant now) {
        jdbc.update("INSERT INTO public.account_email_outbox (id, purpose, subject_type, subject_id, tenant_id, language, recipient_email, state, attempts, available_at, created_at) VALUES (?, ?, 'STAFF', ?, ?, ?, ?, 'PENDING', 0, ?, ?)", UUID.randomUUID().toString(), purpose, target.id(), tenant, language, email, now.toEpochMilli(), now.toEpochMilli());
    }
    private static boolean validTenant(String tenant) { return tenant != null && tenant.matches("[a-z0-9]{1,56}") && !java.util.Set.of("default", "admin", "kebab").contains(tenant); }
    private static String schema(String tenant) { if (!validTenant(tenant)) throw new SecurityException("Invalid tenant"); return "tenant_" + tenant; }
}
