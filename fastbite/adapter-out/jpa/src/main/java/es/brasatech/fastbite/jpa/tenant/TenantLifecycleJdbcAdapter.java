package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.TenantLifecyclePort;
import es.brasatech.fastbite.application.tenant.TenantRegistrationRules;
import es.brasatech.fastbite.domain.tenant.TenantProvisioningInProgressException;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.*;

@Component
public class TenantLifecycleJdbcAdapter implements TenantLifecyclePort {

    /**
     * Lease duration for schema provisioning. Must exceed a cold tenant migration
     * so concurrent workers recognize active setup and don't take over a live job.
     */
    private static final long PROVISIONING_LEASE_MS = Duration.ofMinutes(2).toMillis();

    private final DataSource dataSource;

    public TenantLifecycleJdbcAdapter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private enum ClaimResult {
        PROCEED,
        ALREADY_COMPLETED
    }

    @Override
    public void register(String tenantId, String operationId, String owner, State successState, Runnable work) {
        String tenant = TenantRegistrationRules.tenantId(tenantId);
        TenantRegistrationRules.owner(owner);
        if (operationId == null || operationId.isBlank() || operationId.length() > 300
                || (successState != State.ACTIVE && successState != State.AWAITING_OWNER)) {
            throw new IllegalArgumentException("Invalid registration operation.");
        }

        ClaimResult claim = claim(tenant, operationId, owner);
        if (claim == ClaimResult.ALREADY_COMPLETED) {
            return;
        }

        try {
            work.run();
            finish(tenant, operationId, successState);
        } catch (RuntimeException e) {
            try {
                fail(tenant, operationId);
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("Restaurant setup failed. Please retry the same registration.");
        }
    }

    private ClaimResult claim(String tenant, String operationId, String owner) {
        return transaction(dataSource, connection -> {
            try (var query = statement(connection,
                    "SELECT operation_id, owner_username, state, updated_at FROM public.tenant_lifecycle WHERE tenant_id = ? FOR UPDATE", tenant);
                 var rows = query.executeQuery()) {
                if (!rows.next()) {
                    try (var locQuery = statement(connection,
                            "SELECT tenant_id FROM public.tenant_locations WHERE LOWER(tenant_id) = ?", tenant);
                         var locRows = locQuery.executeQuery()) {
                        if (locRows.next()) {
                            throw new IllegalArgumentException("Restaurant identifier is already registered.");
                        }
                    }
                    var savepoint = connection.setSavepoint();
                    try {
                        update(connection,
                                "INSERT INTO public.tenant_lifecycle (tenant_id, operation_id, owner_username, state, updated_at) VALUES (?, ?, ?, 'PROVISIONING', ?)",
                                tenant, operationId, owner, Instant.now().toEpochMilli());
                        return ClaimResult.PROCEED;
                    } catch (SQLException e) {
                        connection.rollback(savepoint);
                        if (!"23505".equals(e.getSQLState())) throw e;
                    }
                }
            }

            try (var query = statement(connection,
                    "SELECT operation_id, owner_username, state, updated_at FROM public.tenant_lifecycle WHERE tenant_id = ? FOR UPDATE", tenant);
                 var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Tenant lifecycle record missing after insert");
                }
                String existingOp = rows.getString(1);
                String existingOwner = rows.getString(2);
                String state = rows.getString(3);
                long updatedAt = rows.getLong(4);

                if (!operationId.equals(existingOp) || !owner.equals(existingOwner)) {
                    throw new IllegalArgumentException("Restaurant identifier is already reserved.");
                }

                if (State.ACTIVE.name().equals(state)) {
                    return ClaimResult.ALREADY_COMPLETED;
                }

                if (State.AWAITING_OWNER.name().equals(state)) {
                    try (var invitation = statement(connection,
                            "SELECT expires_at FROM public.owner_setup_tokens WHERE tenant_id = ? AND completed = FALSE", tenant);
                         var token = invitation.executeQuery()) {
                        if (token.next() && token.getLong(1) > Instant.now().toEpochMilli()) {
                            return ClaimResult.ALREADY_COMPLETED;
                        }
                    }
                }

                long now = Instant.now().toEpochMilli();
                if ("PROVISIONING".equals(state)) {
                    if (now - updatedAt < PROVISIONING_LEASE_MS) {
                        throw new TenantProvisioningInProgressException("Restaurant setup is currently in progress. Please retry shortly.");
                    }
                    update(connection,
                            "UPDATE public.tenant_lifecycle SET updated_at = ? WHERE tenant_id = ? AND operation_id = ? AND state = 'PROVISIONING'",
                            now, tenant, operationId);
                    return ClaimResult.PROCEED;
                }

                if ("FAILED".equals(state)) {
                    update(connection,
                            "UPDATE public.tenant_lifecycle SET state = 'PROVISIONING', updated_at = ? WHERE tenant_id = ? AND operation_id = ?",
                            now, tenant, operationId);
                    return ClaimResult.PROCEED;
                }

                update(connection,
                        "UPDATE public.tenant_lifecycle SET state = 'PROVISIONING', updated_at = ? WHERE tenant_id = ? AND operation_id = ?",
                        now, tenant, operationId);
                return ClaimResult.PROCEED;
            }
        });
    }

    private void finish(String tenant, String operationId, State successState) {
        transaction(dataSource, connection -> {
            update(connection,
                    "UPDATE public.tenant_lifecycle SET state = ?, updated_at = ? WHERE tenant_id = ? AND operation_id = ? AND state = 'PROVISIONING'",
                    successState.name(), Instant.now().toEpochMilli(), tenant, operationId);
            return null;
        });
    }

    private void fail(String tenant, String operationId) {
        transaction(dataSource, connection -> {
            update(connection,
                    "UPDATE public.tenant_lifecycle SET state = 'FAILED', updated_at = ? WHERE tenant_id = ? AND operation_id = ? AND state = 'PROVISIONING'",
                    Instant.now().toEpochMilli(), tenant, operationId);
            return null;
        });
    }
}
