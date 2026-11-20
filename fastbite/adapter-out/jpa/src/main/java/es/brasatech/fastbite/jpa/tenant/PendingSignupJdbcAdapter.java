package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.tenant.PendingSignupPort;
import es.brasatech.fastbite.application.tenant.TenantRegistrationRules;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.*;

@Component
public class PendingSignupJdbcAdapter implements PendingSignupPort {
    private final DataSource source;
    private final PlatformEnvironment environment;

    public PendingSignupJdbcAdapter(DataSource source) {
        this(source, (PlatformEnvironment) null);
    }

    public PendingSignupJdbcAdapter(DataSource source, PlatformEnvironment environment) {
        this.source = source;
        this.environment = environment;
    }

    @Autowired
    public PendingSignupJdbcAdapter(DataSource source, ObjectProvider<PlatformEnvironment> environment) {
        this(source, environment.getIfAvailable());
    }

    @Override public Start start(Signup signup, Instant now) {
        return transaction(source, connection -> {
            lock(connection);
            expireLocked(connection, now);
            String id = UUID.randomUUID().toString();
            // Public identifiers must be checked before email ownership to avoid revealing an account.
            if (exists(connection, "SELECT tenant_id FROM public.tenant_locations WHERE LOWER(tenant_id) = ?", signup.tenantId())
                    || exists(connection, "SELECT tenant_id FROM public.tenant_lifecycle WHERE tenant_id = ?", signup.tenantId())
                    || exists(connection, "SELECT tenant_id FROM public.pending_registrations WHERE tenant_id = ? AND email <> ?", signup.tenantId(), signup.email()))
                throw new IllegalArgumentException("Choose an available restaurant address.");
            if (exists(connection, "SELECT username FROM public.users WHERE username = ?", signup.username())
                    || exists(connection, "SELECT username FROM public.pending_registrations WHERE username = ? AND email <> ?", signup.username(), signup.email()))
                throw new IllegalArgumentException("Choose an available username.");
            if (emailOwnedOrUsername(connection, signup.email())) {
                if (verifiedEmailExists(connection, signup.email())) enqueue(connection, id, "SIGNUP_NOTICE", signup.email(), signup.language(), now);
                return new Start(id, false);
            }
            // A newer signup at the same unverified address replaces the earlier one.
            String oldId = scalar(connection, "SELECT id FROM public.pending_registrations WHERE email = ?", signup.email());
            if (oldId != null) removePending(connection, oldId, now);
            update(connection, "INSERT INTO public.pending_registrations (id, tenant_id, username, full_name, password_hash, plan, email, language, terms_version, accepted_at, marketing, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, signup.tenantId(), signup.username(), signup.fullName(), signup.passwordHash(), signup.plan(), signup.email(), signup.language(), signup.termsVersion(), signup.acceptedAt().toEpochMilli(), signup.marketing(), now.plus(Duration.ofHours(48)).toEpochMilli());
            enqueue(connection, id, "SIGNUP_VERIFY", signup.email(), signup.language(), now);
            return new Start(id, true);
        });
    }
    @Override public boolean changeEmail(String id, String email, Instant now) {
        return transaction(source, connection -> {
            lock(connection); expireLocked(connection, now);
            Pending existing = load(connection, id);
            if (existing == null) return false;
            if (emailOwnedOrUsername(connection, email)) {
                removePending(connection, id, now);
                if (verifiedEmailExists(connection, email)) enqueue(connection, id, "SIGNUP_NOTICE", email, existing.signup().language(), now);
                return true;
            }
            String old = scalar(connection, "SELECT id FROM public.pending_registrations WHERE email = ?", email);
            if (old != null && !old.equals(id)) removePending(connection, old, now);
            cancelMessages(connection, id, now);
            update(connection, "DELETE FROM public.account_tokens WHERE subject_type = 'PENDING' AND subject_id = ?", id);
            update(connection, "UPDATE public.pending_registrations SET email = ? WHERE id = ?", email, id);
            enqueue(connection, id, "SIGNUP_VERIFY", email, existing.signup().language(), now);
            return true;
        });
    }
    @Override public boolean resend(String id, Instant now) {
        return transaction(source, connection -> {
            lock(connection); expireLocked(connection, now);
            Pending existing = load(connection, id);
            if (existing == null) return false;
            enqueue(connection, id, "SIGNUP_VERIFY", existing.signup().email(), existing.signup().language(), now);
            return true;
        });
    }
    @Override public Optional<Pending> forBrowser(String id, Instant now) {
        return transaction(source, connection -> {
            Pending item = load(connection, id);
            return item == null || !item.expiresAt().isAfter(now) ? Optional.empty() : Optional.of(item);
        });
    }
    @Override public Optional<Pending> forToken(String hash, Instant now) {
        return transaction(source, connection -> {
            try (var query = statement(connection, "SELECT subject_id FROM public.account_tokens WHERE token_hash = ? AND purpose = 'SIGNUP_VERIFY' AND subject_type = 'PENDING' AND consumed_at IS NULL AND expires_at > ?", hash, now.toEpochMilli()); var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                Pending item = load(connection, rows.getString(1));
                return item == null || !item.expiresAt().isAfter(now) ? Optional.empty() : Optional.of(item);
            }
        });
    }
    @Override public boolean alreadyVerified(String hash) {
        return transaction(source, connection -> exists(connection, "SELECT t.token_hash FROM public.account_tokens t JOIN public.users u ON u.id = t.subject_id WHERE t.token_hash = ? AND t.purpose = 'SIGNUP_VERIFY' AND t.subject_type = 'OWNER' AND t.consumed_at IS NOT NULL AND u.email_verified_at IS NOT NULL AND u.email = t.email", hash));
    }
    @Override public boolean isPending(String id, Instant now) {
        return transaction(source, connection -> exists(connection, "SELECT id FROM public.pending_registrations WHERE id = ? AND expires_at > ?", id, now.toEpochMilli()));
    }
    @Override public boolean usernameAvailable(String username, Instant now) {
        return transaction(source, connection -> !exists(connection, "SELECT username FROM public.users WHERE username = ?", username)
                && !exists(connection, "SELECT username FROM public.pending_registrations WHERE username = ? AND expires_at > ?", username, now.toEpochMilli()));
    }
    @Override public int expire(Instant now) { return transaction(source, connection -> { lock(connection); return expireLocked(connection, now); }); }

    @Override public boolean complete(String hash, String id, String tenant, String username, Instant now) {
        return transaction(source, connection -> {
            lock(connection);
            Pending item = load(connection, id);
            if (item == null || !item.expiresAt().isAfter(now)) return abandonClaim(connection, tenant, username);
            var data = item.signup();
            if (!data.tenantId().equals(tenant) || !data.username().equals(username)
                    || !exists(connection, "SELECT token_hash FROM public.account_tokens WHERE token_hash = ? AND purpose = 'SIGNUP_VERIFY' AND subject_type = 'PENDING' AND subject_id = ? AND consumed_at IS NULL AND expires_at > ? AND email = ?", hash, id, now.toEpochMilli(), data.email()))
                return abandonClaim(connection, tenant, username);
            String schema = "tenant_" + TenantRegistrationRules.tenantId(data.tenantId());
            String userId = UUID.randomUUID().toString();
            for (String target : new String[]{"public", schema}) {
                update(connection, "INSERT INTO " + target + ".users (id, username, password, full_name, active, tenant_id, email, email_verified_at, account_language) VALUES (?, ?, ?, ?, TRUE, ?, ?, ?, ?)", userId, data.username(), data.passwordHash(), data.fullName(), data.tenantId(), data.email(), now.toEpochMilli(), data.language());
                for (String role : new String[]{"OWNER", "ADMIN"}) update(connection, "INSERT INTO " + target + ".user_roles (user_id, role) VALUES (?, ?)", userId, role);
            }
            update(connection, "INSERT INTO public.tenant_locations (id, owner_username, tenant_id, plan) VALUES (?, ?, ?, ?)", UUID.randomUUID().toString(), data.username(), data.tenantId(), data.plan());
            Instant trialStartedAt = (environment != null && environment.production()) ? null : now;
            BillingJdbcAdapter.createTrial(connection, data.tenantId(), "FREE".equalsIgnoreCase(data.plan()) ? "FREE" : "LOCAL_TRIAL", trialStartedAt);
            recordConsent(connection, data, "terms");
            if (data.marketing()) recordConsent(connection, data, "marketing");
            update(connection, "UPDATE public.account_tokens SET subject_type = 'OWNER', subject_id = ?, consumed_at = ? WHERE token_hash = ?", userId, now.toEpochMilli(), hash);
            update(connection, "DELETE FROM public.account_tokens WHERE subject_type = 'PENDING' AND subject_id = ?", id);
            cancelMessages(connection, id, now);
            update(connection, "DELETE FROM public.pending_registrations WHERE id = ?", id);
            update(connection, "UPDATE public.tenant_lifecycle SET state = 'ACTIVE', updated_at = ? WHERE tenant_id = ? AND operation_id = ?", now.toEpochMilli(), data.tenantId(), "signup:" + data.tenantId());
            return true;
        });
    }
    private static boolean abandonClaim(Connection connection, String tenant, String username) throws SQLException {
        update(connection, "DELETE FROM public.tenant_lifecycle WHERE tenant_id = ? AND operation_id = ? AND owner_username = ? AND state = 'PROVISIONING'", tenant, "signup:" + tenant, username);
        return false;
    }
    private static void recordConsent(Connection connection, Signup data, String document) throws SQLException {
        update(connection, "INSERT INTO public.legal_acceptances (id, username, document, version, accepted_at) VALUES (?, ?, ?, ?, ?)", UUID.randomUUID().toString(), data.username(), document, data.termsVersion(), Timestamp.from(data.acceptedAt()));
    }
    private static void lock(Connection connection) throws SQLException {
        try (var query = statement(connection, "SELECT id FROM public.signup_reservation_lock WHERE id = 1 FOR UPDATE"); var rows = query.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("Signup lock is missing");
        }
    }
    private static boolean emailOwnedOrUsername(Connection c, String email) throws SQLException {
        return exists(c, "SELECT id FROM public.users WHERE LOWER(email) = ? OR LOWER(username) = ?", email, email);
    }
    private static boolean verifiedEmailExists(Connection c, String email) throws SQLException {
        return exists(c, "SELECT id FROM public.users WHERE email = ? AND email_verified_at IS NOT NULL", email);
    }
    private static boolean exists(Connection c, String sql, Object... args) throws SQLException {
        try (var query = statement(c, sql, args); var rows = query.executeQuery()) { return rows.next(); }
    }
    private static String scalar(Connection c, String sql, Object... args) throws SQLException {
        try (var query = statement(c, sql, args); var rows = query.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
    }
    private static Pending load(Connection c, String id) throws SQLException {
        try (var query = statement(c, "SELECT tenant_id, username, full_name, password_hash, plan, email, language, terms_version, accepted_at, marketing, expires_at FROM public.pending_registrations WHERE id = ?", id); var rows = query.executeQuery()) {
            if (!rows.next()) return null;
            return new Pending(id, new Signup(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7), rows.getString(8), Instant.ofEpochMilli(rows.getLong(9)), rows.getBoolean(10)), Instant.ofEpochMilli(rows.getLong(11)));
        }
    }
    private static void enqueue(Connection c, String id, String purpose, String email, String language, Instant now) throws SQLException {
        if (exists(c, "SELECT id FROM public.account_email_outbox WHERE subject_id = ? AND purpose = ? AND state IN ('PENDING', 'CLAIMED')", id, purpose)) return;
        update(c, "INSERT INTO public.account_email_outbox (id, purpose, subject_type, subject_id, language, recipient_email, state, attempts, available_at, created_at) VALUES (?, ?, 'PENDING', ?, ?, ?, 'PENDING', 0, ?, ?)", UUID.randomUUID().toString(), purpose, id, language, email, now.toEpochMilli(), now.toEpochMilli());
    }
    private static int expireLocked(Connection c, Instant now) throws SQLException {
        java.util.List<String> expired = new java.util.ArrayList<>();
        try (var query = statement(c, "SELECT id FROM public.pending_registrations WHERE expires_at <= ?", now.toEpochMilli()); var rows = query.executeQuery()) { while (rows.next()) expired.add(rows.getString(1)); }
        for (String id : expired) removePending(c, id, now);
        return expired.size();
    }
    private static void removePending(Connection c, String id, Instant now) throws SQLException {
        cancelMessages(c, id, now);
        update(c, "DELETE FROM public.account_tokens WHERE subject_type = 'PENDING' AND subject_id = ?", id);
        update(c, "DELETE FROM public.pending_registrations WHERE id = ?", id);
    }
    private static void cancelMessages(Connection c, String id, Instant now) throws SQLException {
        update(c, "UPDATE public.account_email_outbox SET state = 'CANCELLED', finished_at = ?, claim_id = NULL, claimed_at = NULL WHERE subject_id = ? AND subject_type = 'PENDING' AND state IN ('PENDING', 'CLAIMED')", now.toEpochMilli(), id);
    }
}
