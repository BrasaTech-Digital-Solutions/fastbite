package es.brasatech.fastbite.jpa.user;

import es.brasatech.fastbite.application.office.PasswordRecoveryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Component
public class PasswordRecoveryJdbcAdapter implements PasswordRecoveryPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public PasswordRecoveryJdbcAdapter(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc = jdbc; this.tx = tx; }
    @Override public Optional<Account> enqueue(String identifier, String tenantId, Instant now) {
        return tx.execute(status -> {
            lock();
            String tenant = validTenant(tenantId) ? tenantId : null;
            String ownerQuery = "SELECT u.id, u.username, u.email, COALESCE(u.account_language, 'en') FROM public.users u WHERE u.active = TRUE AND u.email IS NOT NULL AND u.email_verified_at IS NOT NULL AND (u.username = ? OR u.email = ?) AND EXISTS (SELECT 1 FROM public.user_roles r WHERE r.user_id = u.id AND r.role = 'OWNER')"
                    + (tenant == null ? "" : " AND EXISTS (SELECT 1 FROM public.tenant_locations l WHERE l.owner_username = u.username AND l.tenant_id = ?)")
                    + " ORDER BY CASE WHEN u.username = ? THEN 0 ELSE 1 END FETCH FIRST 1 ROWS ONLY FOR UPDATE";
            Object[] ownerArgs = tenant == null ? new Object[]{identifier, identifier.toLowerCase(Locale.ROOT), identifier}
                    : new Object[]{identifier, identifier.toLowerCase(Locale.ROOT), tenant, identifier};
            var owner = jdbc.query(ownerQuery, (r, n) -> new String[]{r.getString(1), r.getString(2), r.getString(3), r.getString(4)}, ownerArgs);
            String[] user = owner.isEmpty() ? null : owner.getFirst(); String type = "OWNER";
            if (user == null && tenant != null) {
                var staff = jdbc.query("SELECT u.id, u.username, u.email, COALESCE(u.account_language, 'en') FROM " + schema(tenant) + ".users u WHERE u.username = ? AND u.active = TRUE AND u.email IS NOT NULL AND u.email_verified_at IS NOT NULL AND NOT EXISTS (SELECT 1 FROM " + schema(tenant) + ".user_roles r WHERE r.user_id = u.id AND r.role = 'OWNER') FOR UPDATE", (r, n) -> new String[]{r.getString(1), r.getString(2), r.getString(3), r.getString(4)}, identifier);
                if (!staff.isEmpty()) { user = staff.getFirst(); type = "STAFF"; }
            }
            if (user == null) return Optional.empty();
            String home = type.equals("OWNER") ? null : tenant;
            jdbc.update("INSERT INTO public.account_email_outbox (id, purpose, subject_type, subject_id, tenant_id, language, recipient_email, state, attempts, available_at, created_at) VALUES (?, 'PASSWORD_RESET', ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)", UUID.randomUUID().toString(), type, user[0], home, user[3], user[2], now.toEpochMilli(), now.toEpochMilli());
            return Optional.of(new Account(user[0], user[1], home));
        });
    }
    @Override public boolean valid(String hash, Instant now) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM public.account_tokens WHERE token_hash = ? AND purpose = 'PASSWORD_RESET' AND consumed_at IS NULL AND expires_at > ?", Integer.class, hash, now.toEpochMilli());
        return n != null && n > 0;
    }
    @Override public Optional<Account> consume(String hash, String passwordHash, Instant now) {
        return tx.execute(status -> {
            lock();
            var tokens = jdbc.query("SELECT subject_id, subject_type, tenant_id, email FROM public.account_tokens WHERE token_hash = ? AND purpose = 'PASSWORD_RESET' AND consumed_at IS NULL AND expires_at > ? FOR UPDATE", (r, n) -> new String[]{r.getString(1), r.getString(2), r.getString(3), r.getString(4)}, hash, now.toEpochMilli());
            if (tokens.isEmpty()) return Optional.empty();
            String[] token = tokens.getFirst(); String home = token[1].equals("OWNER") ? null : token[2];
            if (home != null && !validTenant(home)) return Optional.empty();
            String target = home == null ? "public" : schema(home);
            var users = jdbc.query("SELECT username FROM " + target + ".users WHERE id = ? AND active = TRUE AND email = ? AND email_verified_at IS NOT NULL FOR UPDATE", (r, n) -> r.getString(1), token[0], token[3]);
            if (users.isEmpty()) return Optional.empty();
            jdbc.update("UPDATE " + target + ".users SET password = ? WHERE id = ?", passwordHash, token[0]);
            jdbc.update("UPDATE public.account_tokens SET consumed_at = ? WHERE token_hash = ?", now.toEpochMilli(), hash);
            jdbc.update("DELETE FROM public.account_tokens WHERE purpose = 'PASSWORD_RESET' AND subject_type = ? AND subject_id = ? AND consumed_at IS NULL", token[1], token[0]);
            return Optional.of(new Account(token[0], users.getFirst(), home));
        });
    }
    private void lock() { jdbc.queryForObject("SELECT id FROM public.signup_reservation_lock WHERE id = 1 FOR UPDATE", Integer.class); }
    private static boolean validTenant(String tenant) { return tenant != null && tenant.matches("[a-z0-9]{1,56}") && !java.util.Set.of("default", "admin", "kebab").contains(tenant); }
    private static String schema(String tenant) { if (!validTenant(tenant)) throw new IllegalArgumentException("Invalid tenant"); return "tenant_" + tenant; }
}
