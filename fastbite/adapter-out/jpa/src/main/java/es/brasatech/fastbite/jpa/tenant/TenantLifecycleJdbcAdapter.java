package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.TenantLifecyclePort;
import es.brasatech.fastbite.application.tenant.TenantRegistrationRules;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;

import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.*;

@Component
public class TenantLifecycleJdbcAdapter implements TenantLifecyclePort {
    private final DataSource dataSource;
    public TenantLifecycleJdbcAdapter(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public void register(String tenantId, String operationId, String owner, State successState, Runnable work) {
        String tenant = TenantRegistrationRules.tenantId(tenantId);
        TenantRegistrationRules.owner(owner);
        if (operationId == null || operationId.isBlank() || operationId.length() > 300
                || (successState != State.ACTIVE && successState != State.AWAITING_OWNER)) {
            throw new IllegalArgumentException("Invalid registration operation.");
        }
        reserve(tenant, operationId, owner);
        // DDL uses a separate connection because H2 commits DDL implicitly.
        // Fail fast when another worker owns the row so waiters cannot exhaust the connection pool.
        RuntimeException failure = transaction(dataSource, connection -> {
            try (var query = statement(connection,
                    "SELECT operation_id, owner_username, state FROM public.tenant_lifecycle WHERE tenant_id = ? FOR UPDATE NOWAIT", tenant);
                 var rows = query.executeQuery()) {
                if (!rows.next() || !operationId.equals(rows.getString(1)) || !owner.equals(rows.getString(2))) {
                    throw new IllegalArgumentException("Restaurant identifier is already reserved.");
                }
                String state = rows.getString(3);
                if (State.ACTIVE.name().equals(state)) return null;
                if (State.AWAITING_OWNER.name().equals(state)) {
                    try (var invitation = statement(connection, "SELECT expires_at FROM public.owner_setup_tokens WHERE tenant_id = ? AND completed = FALSE", tenant);
                         var token = invitation.executeQuery()) {
                        if (token.next() && token.getLong(1) > Instant.now().toEpochMilli()) return null;
                    }
                }
            }
            try {
                work.run();
                update(connection, "UPDATE public.tenant_lifecycle SET state = ?, updated_at = ? WHERE tenant_id = ?",
                        successState.name(), Instant.now().toEpochMilli(), tenant);
                return null;
            } catch (RuntimeException e) {
                update(connection, "UPDATE public.tenant_lifecycle SET state = 'FAILED', updated_at = ? WHERE tenant_id = ?",
                        Instant.now().toEpochMilli(), tenant);
                return new IllegalStateException("Restaurant setup failed. Please retry the same registration.");
            }
        });
        if (failure != null) throw failure;
    }

    private void reserve(String tenant, String operationId, String owner) {
        transaction(dataSource, connection -> {
            try (var query = statement(connection, "SELECT tenant_id FROM public.tenant_lifecycle WHERE tenant_id = ?", tenant);
                 var rows = query.executeQuery()) {
                if (rows.next()) return null;
            }
            try (var query = statement(connection, "SELECT tenant_id FROM public.tenant_locations WHERE LOWER(tenant_id) = ?", tenant);
                 var rows = query.executeQuery()) {
                if (rows.next()) throw new IllegalArgumentException("Restaurant identifier is already registered.");
            }
            // A competing insert may win. Verify its owner and operation under the row lock above.
            var savepoint = connection.setSavepoint();
            try {
                update(connection, "INSERT INTO public.tenant_lifecycle (tenant_id, operation_id, owner_username, state, updated_at) VALUES (?, ?, ?, 'PROVISIONING', ?)",
                        tenant, operationId, owner, Instant.now().toEpochMilli());
            } catch (SQLException e) {
                connection.rollback(savepoint);
                if (!"23505".equals(e.getSQLState())) throw e;
            }
            return null;
        });
    }
}
