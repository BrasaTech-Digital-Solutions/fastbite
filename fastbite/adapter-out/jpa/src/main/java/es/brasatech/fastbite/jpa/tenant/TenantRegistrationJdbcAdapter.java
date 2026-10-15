package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.TenantRegistrationPort;
import es.brasatech.fastbite.application.tenant.TenantRegistrationRules;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.*;

@Component
public class TenantRegistrationJdbcAdapter implements TenantRegistrationPort {
    private final DataSource dataSource;
    public TenantRegistrationJdbcAdapter(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public void requireAvailableUsername(String tenant, String username) {
        transaction(dataSource, connection -> {
            try (var query = statement(connection, "SELECT tenant_id FROM public.users WHERE username = ?", username);
                 var rows = query.executeQuery()) {
                if (rows.next() && !tenant.equals(rows.getString(1))) {
                    throw new IllegalArgumentException("Owner username is already registered.");
                }
            }
            return null;
        });
    }

    @Override
    public void createOwner(String tenant, String username, String encodedPassword, String fullName, String plan) {
        String schema = "tenant_" + TenantRegistrationRules.tenantId(tenant);
        transaction(dataSource, connection -> {
            // Recover a crash after these writes committed but before the lifecycle update.
            if (sameLocation(connection, tenant, username)) return null;
            String id = UUID.randomUUID().toString();
            for (String target : new String[]{"public", schema}) {
                update(connection, "INSERT INTO " + target + ".users (id, username, password, full_name, active, tenant_id) VALUES (?, ?, ?, ?, TRUE, ?)",
                        id, username, encodedPassword, fullName, tenant);
                for (String role : new String[]{"OWNER", "ADMIN"}) {
                    update(connection, "INSERT INTO " + target + ".user_roles (user_id, role) VALUES (?, ?)", id, role);
                }
            }
            insertLocation(connection, tenant, username, plan);
            return null;
        });
    }

    @Override
    public void createOwner(String tenant, String username, String encodedPassword, String fullName) {
        createOwner(tenant, username, encodedPassword, fullName, "RESTAURANT");
    }

    @Override
    public void requireActiveOwner(String username) {
        transaction(dataSource, connection -> {
            try (var query = statement(connection, "SELECT u.id FROM public.users u JOIN public.user_roles r ON r.user_id = u.id WHERE u.username = ? AND u.active = TRUE AND r.role = 'OWNER'", username);
                 var rows = query.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("An active owner account is required.");
            }
            return null;
        });
    }

    @Override
    public void createLocation(String tenant, String owner, String plan) {
        transaction(dataSource, connection -> {
            if (!sameLocation(connection, tenant, owner)) insertLocation(connection, tenant, owner, plan);
            return null;
        });
    }

    private static boolean sameLocation(Connection connection, String tenant, String owner) throws SQLException {
        try (var query = statement(connection, "SELECT owner_username FROM public.tenant_locations WHERE tenant_id = ?", tenant);
             var rows = query.executeQuery()) {
            if (!rows.next()) return false;
            if (!owner.equals(rows.getString(1))) throw new IllegalArgumentException("Restaurant belongs to another owner.");
            return true;
        }
    }

    private static void insertLocation(Connection connection, String tenant, String owner, String plan) throws SQLException {
        String effectivePlan = (plan != null && !plan.isBlank()) ? plan.trim() : "RESTAURANT";
        update(connection, "INSERT INTO public.tenant_locations (id, owner_username, tenant_id, plan) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), owner, tenant, effectivePlan);
        String initialBillingStatus = "FREE".equalsIgnoreCase(effectivePlan) ? "FREE" : "LOCAL_TRIAL";
        BillingJdbcAdapter.createTrial(connection, tenant, initialBillingStatus);
    }
}
