package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.platform.SandboxProvisioningPort;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.statement;
import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.transaction;
import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.update;

/** Production's list of locations to set up in the sandbox, in the platform schema. */
@Component
public class SandboxProvisioningJdbcAdapter implements SandboxProvisioningPort {

    private static final String SELECT = "SELECT p.tenant_id, p.owner_username, u.full_name, p.plan, p.state, p.attempts, "
            + "p.last_error, p.next_attempt_at FROM public.sandbox_provisioning p "
            + "LEFT JOIN public.users u ON u.username = p.owner_username ";

    private final DataSource dataSource;

    public SandboxProvisioningJdbcAdapter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void enqueue(String tenantId, String ownerUsername, String plan, Instant now) {
        transaction(dataSource, connection -> {
            if (find(connection, tenantId).isEmpty()) {
                update(connection, "INSERT INTO public.sandbox_provisioning (tenant_id, owner_username, plan, state, attempts, "
                                + "next_attempt_at, created_at) VALUES (?, ?, ?, ?, 0, ?, ?)",
                        tenantId, ownerUsername, plan, State.PENDING.name(), now.toEpochMilli(), now.toEpochMilli());
            }
            return null;
        });
    }

    @Override
    public List<Entry> due(Instant now, int limit) {
        return transaction(dataSource, connection -> {
            List<Entry> due = new ArrayList<>();
            try (var query = statement(connection, SELECT + "WHERE p.state = ? AND p.next_attempt_at <= ? "
                    + "ORDER BY p.created_at LIMIT " + Math.max(1, limit), State.PENDING.name(), now.toEpochMilli());
                 var rows = query.executeQuery()) {
                while (rows.next()) {
                    due.add(entry(rows));
                }
            }
            return due;
        });
    }

    @Override
    public Optional<Entry> find(String tenantId) {
        return transaction(dataSource, connection -> find(connection, tenantId));
    }

    @Override
    public void done(String tenantId, Instant now) {
        transaction(dataSource, connection -> update(connection, "UPDATE public.sandbox_provisioning SET state = ?, "
                + "last_error = NULL, done_at = ? WHERE tenant_id = ?", State.DONE.name(), now.toEpochMilli(), tenantId));
    }

    @Override
    public void failed(String tenantId, String error, Instant nextAttempt) {
        String reason = error == null ? null : error.substring(0, Math.min(500, error.length()));
        transaction(dataSource, connection -> update(connection, "UPDATE public.sandbox_provisioning SET attempts = attempts + 1, "
                + "last_error = ?, next_attempt_at = ? WHERE tenant_id = ? AND state = ?", reason,
                nextAttempt.toEpochMilli(), tenantId, State.PENDING.name()));
    }

    private static Optional<Entry> find(Connection connection, String tenantId) throws SQLException {
        try (var query = statement(connection, SELECT + "WHERE p.tenant_id = ?", tenantId);
             var rows = query.executeQuery()) {
            return rows.next() ? Optional.of(entry(rows)) : Optional.empty();
        }
    }

    private static Entry entry(ResultSet rows) throws SQLException {
        return new Entry(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                State.valueOf(rows.getString(5)), rows.getInt(6), rows.getString(7), Instant.ofEpochMilli(rows.getLong(8)));
    }
}
