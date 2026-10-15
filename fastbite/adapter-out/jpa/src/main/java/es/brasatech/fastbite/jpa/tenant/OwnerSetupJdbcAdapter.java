package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.OwnerSetupPort;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/** Explicit schema names avoid request tenant context, search_path and Hibernate session leakage. */
@Component
public class OwnerSetupJdbcAdapter implements OwnerSetupPort {
    private final DataSource dataSource;

    public OwnerSetupJdbcAdapter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean prepare(Invitation invitation, String tokenHash, Instant expiresAt) {
        return transaction(connection -> {
            try (var query = statement(connection, "SELECT tenant_id, username, email, completed FROM public.owner_setup_tokens WHERE checkout_id = ? FOR UPDATE", invitation.checkoutId());
                 var rows = query.executeQuery()) {
                if (rows.next()) {
                    if (!invitation.tenantId().equals(rows.getString(1)) || !invitation.username().equals(rows.getString(2))
                            || !invitation.email().equals(rows.getString(3))) {
                        throw new IllegalArgumentException("Checkout owner details changed");
                    }
                    if (rows.getBoolean(4)) return false;
                    update(connection, "UPDATE public.owner_setup_tokens SET token_hash = ?, expires_at = ? WHERE checkout_id = ?",
                            tokenHash, expiresAt.toEpochMilli(), invitation.checkoutId());
                    return true;
                }
            }
            String schema = tenantSchema(invitation.tenantId());
            String userId = UUID.randomUUID().toString();
            // INSERT, never upsert: a paid checkout must not overwrite an existing platform account.
            createUser(connection, "public", userId, invitation);
            createUser(connection, schema, userId, invitation);
            update(connection, "INSERT INTO public.tenant_locations (id, owner_username, tenant_id, plan) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID().toString(), invitation.username(), invitation.tenantId(), "RESTAURANT");
            BillingJdbcAdapter.createTrial(connection, invitation.tenantId());
            update(connection, "INSERT INTO public.owner_setup_tokens (checkout_id, tenant_id, username, email, user_id, token_hash, expires_at, completed) VALUES (?, ?, ?, ?, ?, ?, ?, FALSE)",
                    invitation.checkoutId(), invitation.tenantId(), invitation.username(), invitation.email(), userId, tokenHash, expiresAt.toEpochMilli());
            return true;
        });
    }

    @Override
    public boolean isValid(String tokenHash, Instant now) {
        return transaction(connection -> {
            try (var query = statement(connection, "SELECT checkout_id FROM public.owner_setup_tokens WHERE token_hash = ? AND completed = FALSE AND expires_at > ?", tokenHash, now.toEpochMilli());
                 var rows = query.executeQuery()) {
                return rows.next();
            }
        });
    }

    @Override
    public boolean complete(String tokenHash, String encodedPassword, Instant now) {
        return transaction(connection -> {
            // Registration locks lifecycle before tokens; use the same order to avoid deadlocks.
            String setupTenant;
            try (var query = statement(connection, "SELECT tenant_id FROM public.owner_setup_tokens WHERE token_hash = ?", tokenHash);
                 var rows = query.executeQuery()) {
                if (!rows.next()) return false;
                setupTenant = rows.getString(1);
            }
            try (var query = statement(connection, "SELECT tenant_id FROM public.tenant_lifecycle WHERE tenant_id = ? FOR UPDATE", setupTenant);
                 var rows = query.executeQuery()) {
                rows.next(); // Legacy invitations may not yet have a lifecycle record.
            }
            try (var query = statement(connection, "SELECT checkout_id, tenant_id, user_id FROM public.owner_setup_tokens WHERE token_hash = ? AND completed = FALSE AND expires_at > ? FOR UPDATE", tokenHash, now.toEpochMilli());
                 var rows = query.executeQuery()) {
                if (!rows.next()) return false;
                String checkoutId = rows.getString(1);
                String tenant = rows.getString(2);
                String userId = rows.getString(3);
                for (String schema : new String[]{"public", tenantSchema(tenant)}) {
                    int updated = update(connection, "UPDATE " + schema + ".users SET password = ?, active = TRUE WHERE id = ? AND tenant_id = ? AND active = FALSE",
                            encodedPassword, userId, tenant);
                    if (updated != 1) throw new IllegalStateException("Pending owner account is missing or already enabled");
                }
                update(connection, "UPDATE public.owner_setup_tokens SET completed = TRUE, token_hash = NULL WHERE checkout_id = ?", checkoutId);
                update(connection, "UPDATE public.tenant_lifecycle SET state = 'ACTIVE', updated_at = ? WHERE tenant_id = ? AND operation_id = ?",
                        now.toEpochMilli(), tenant, "setup:" + checkoutId);
                return true;
            }
        });
    }

    private static void createUser(Connection connection, String schema, String userId, Invitation invitation) throws SQLException {
        update(connection, "INSERT INTO " + schema + ".users (id, username, password, full_name, active, tenant_id) VALUES (?, ?, ?, ?, FALSE, ?)",
                userId, invitation.username(), "!pending-password-setup", invitation.fullName(), invitation.tenantId());
        for (String role : new String[]{"OWNER", "ADMIN"}) {
            update(connection, "INSERT INTO " + schema + ".user_roles (user_id, role) VALUES (?, ?)", userId, role);
        }
    }

    private static String tenantSchema(String tenant) {
        if (tenant == null || !tenant.matches("[a-z0-9]{1,56}") || java.util.Set.of("default", "admin", "kebab").contains(tenant)) {
            throw new IllegalArgumentException("Invalid setup tenant");
        }
        return "tenant_" + tenant;
    }

    private static PreparedStatement statement(Connection connection, String sql, Object... values) throws SQLException {
        var statement = connection.prepareStatement(sql);
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
        return statement;
    }

    private static int update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = statement(connection, sql, values)) {
            return statement.executeUpdate();
        }
    }

    private <T> T transaction(SqlWork<T> work) {
        try (var connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                // Do not expose SQL parameter values (token hashes or password hashes).
                throw new IllegalStateException("Owner password setup could not be persisted");
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Owner password setup database unavailable");
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
