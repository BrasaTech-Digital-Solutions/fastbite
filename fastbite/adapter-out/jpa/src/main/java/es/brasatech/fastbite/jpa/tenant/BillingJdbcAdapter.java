package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.BillingPort;
import es.brasatech.fastbite.domain.tenant.BillingAccount;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;
import java.util.Optional;
import java.util.function.UnaryOperator;
import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.*;

@Component
public class BillingJdbcAdapter implements BillingPort {
    private final DataSource source;
    public BillingJdbcAdapter(DataSource source) { this.source = source; }
    @Override public Optional<BillingAccount> find(String tenant) {
        return transaction(source, c -> read(c, tenant));
    }
    @Override public java.util.List<BillingAccount> findAll() {
        return transaction(source, c -> {
            var list = new java.util.ArrayList<BillingAccount>();
            try (var q = statement(c, "SELECT b.*, l.owner_username, l.stripe_account_id, l.stripe_charges_enabled FROM public.tenant_billing b JOIN public.tenant_locations l ON l.tenant_id=b.tenant_id ORDER BY b.tenant_id");
                    var r = q.executeQuery()) {
                while (r.next()) {
                    list.add(new BillingAccount(r.getString("tenant_id"), r.getString("owner_username"), r.getString("billing_key"), r.getTimestamp("trial_started_at").toInstant(),
                            r.getString("status"), r.getString("customer_id"), r.getString("subscription_id"), r.getLong("period_end"), r.getLong("stripe_trial_end"), r.getBoolean("cancel_at_period_end"),
                            r.getString("checkout_id"), r.getString("checkout_url"), r.getLong("checkout_expires"), r.getInt("checkout_attempt"),
                            r.getString("stripe_account_id"), r.getBoolean("stripe_charges_enabled")));
                }
            }
            return java.util.List.copyOf(list);
        });
    }
    @Override public BillingAccount updateLocked(String tenant, UnaryOperator<BillingAccount> action) {
        return transaction(source, c -> {
            try (var lock = statement(c, "SELECT tenant_id FROM public.tenant_billing WHERE tenant_id = ? FOR UPDATE", tenant);
                    var rows = lock.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Billing is not available for this location.");
            }
            var before = read(c, tenant).orElseThrow();
            var after = action.apply(before);
            update(c, "UPDATE public.tenant_billing SET status=?, customer_id=?, subscription_id=?, period_end=?, stripe_trial_end=?, cancel_at_period_end=?, checkout_id=?, checkout_url=?, checkout_expires=?, checkout_attempt=? WHERE tenant_id=?",
                    after.status(), after.customerId(), after.subscriptionId(), after.periodEnd(), after.stripeTrialEnd(), after.cancelAtPeriodEnd(), after.checkoutId(), after.checkoutUrl(), after.checkoutExpires(), after.attempt(), tenant);
            return after;
        });
    }
    private Optional<BillingAccount> read(Connection c, String tenant) throws SQLException {
        try (var q = statement(c, "SELECT b.*, l.owner_username, l.stripe_account_id, l.stripe_charges_enabled FROM public.tenant_billing b JOIN public.tenant_locations l ON l.tenant_id=b.tenant_id WHERE b.tenant_id=?", tenant);
                var r = q.executeQuery()) {
            if (!r.next()) return Optional.empty();
            return Optional.of(new BillingAccount(tenant, r.getString("owner_username"), r.getString("billing_key"), r.getTimestamp("trial_started_at").toInstant(),
                    r.getString("status"), r.getString("customer_id"), r.getString("subscription_id"), r.getLong("period_end"), r.getLong("stripe_trial_end"), r.getBoolean("cancel_at_period_end"),
                    r.getString("checkout_id"), r.getString("checkout_url"), r.getLong("checkout_expires"), r.getInt("checkout_attempt"),
                    r.getString("stripe_account_id"), r.getBoolean("stripe_charges_enabled")));
        }
    }
    @Override public void requestGroupQuote(String owner, String email, int locations) {
        transaction(source, c -> {
            if (update(c, "UPDATE public.group_quote_requests SET email=?, locations=?, requested_at=CURRENT_TIMESTAMP WHERE owner_username=?", email, locations, owner) == 0)
                update(c, "INSERT INTO public.group_quote_requests (owner_username,email,locations) VALUES (?,?,?)", owner, email, locations);
            return null;
        });
    }
    @Override public es.brasatech.fastbite.domain.tenant.PlatformBillingSettings getPlatformSettings() {
        return es.brasatech.fastbite.domain.tenant.PlatformBillingSettings.defaultSettings();
    }

    @Override public void updatePlatformSettings(es.brasatech.fastbite.domain.tenant.PlatformBillingSettings s) {
        // Platform settings are managed via configuration/defaults for now.
    }

    @Override public void updateTenantPlan(String tenantId, String plan) {
        transaction(source, c -> {
            update(c, "UPDATE public.tenant_locations SET plan=? WHERE tenant_id=?", plan, tenantId);
            if ("FREE".equalsIgnoreCase(plan)) {
                update(c, "UPDATE public.tenant_billing SET status='FREE' WHERE tenant_id=?", tenantId);
                // Safe SQL identifier: TenantRegistrationRules.tenantId strictly validates [a-z0-9]{1,50}.
                String schema = "tenant_" + es.brasatech.fastbite.application.tenant.TenantRegistrationRules.tenantId(tenantId);
                try (var check = statement(c, "SELECT 1 FROM information_schema.tables WHERE LOWER(table_schema) = LOWER(?) AND LOWER(table_name) = 'restaurant_settings'", schema);
                        var rows = check.executeQuery()) {
                    if (rows.next()) {
                        update(c, "UPDATE " + schema + ".restaurant_settings SET takeaway = TRUE WHERE id = 1");
                    }
                }
            } else {
                // Keep trial_started_at intact so switching Free -> Restaurant only uses any remaining original trial window.
                update(c, "UPDATE public.tenant_billing SET status='LOCAL_TRIAL' WHERE tenant_id=? AND LOWER(status)='free' AND subscription_id IS NULL", tenantId);
            }
            return null;
        });
    }

    @Override public void extendTenantTrial(String tenantId, int extraDays) {
        transaction(source, c -> {
            update(c, "UPDATE public.tenant_billing SET trial_started_at = trial_started_at + (? * INTERVAL '1 day') WHERE tenant_id=?", extraDays, tenantId);
            return null;
        });
    }

    static void createTrial(Connection c, String tenant) throws SQLException {
        createTrial(c, tenant, "LOCAL_TRIAL");
    }
    static void createTrial(Connection c, String tenant, String status) throws SQLException {
        update(c, "INSERT INTO public.tenant_billing (tenant_id,billing_key,status) SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM public.tenant_billing WHERE tenant_id=?)", tenant, java.util.UUID.randomUUID().toString(), status != null ? status : "LOCAL_TRIAL", tenant);
    }
}
