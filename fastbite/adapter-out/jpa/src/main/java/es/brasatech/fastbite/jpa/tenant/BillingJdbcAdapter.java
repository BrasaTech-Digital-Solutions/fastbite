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
                    list.add(mapAccount(r));
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
            update(c, "UPDATE public.tenant_billing SET status=?, customer_id=?, subscription_id=?, period_end=?, stripe_trial_end=?, cancel_at_period_end=?, checkout_id=?, checkout_url=?, checkout_expires=?, checkout_attempt=?, paused_since=?, pause_notified_at=?, warning_notified_at=?, contract_ended_at=?, closing_started_at=?, closed_at=? WHERE tenant_id=?",
                    after.status(), after.customerId(), after.subscriptionId(), after.periodEnd(), after.stripeTrialEnd(), after.cancelAtPeriodEnd(), after.checkoutId(), after.checkoutUrl(), after.checkoutExpires(), after.attempt(),
                    toTimestamp(after.pausedSince()), toTimestamp(after.pauseNotifiedAt()), toTimestamp(after.warningNotifiedAt()), toTimestamp(after.contractEndedAt()), toTimestamp(after.closingStartedAt()), toTimestamp(after.closedAt()), tenant);
            return after;
        });
    }
    private Optional<BillingAccount> read(Connection c, String tenant) throws SQLException {
        try (var q = statement(c, "SELECT b.*, l.owner_username, l.stripe_account_id, l.stripe_charges_enabled FROM public.tenant_billing b JOIN public.tenant_locations l ON l.tenant_id=b.tenant_id WHERE b.tenant_id=?", tenant);
                var r = q.executeQuery()) {
            if (!r.next()) return Optional.empty();
            return Optional.of(mapAccount(r));
        }
    }
    private static BillingAccount mapAccount(ResultSet r) throws SQLException {
        return new BillingAccount(r.getString("tenant_id"), r.getString("owner_username"), r.getString("billing_key"), r.getTimestamp("trial_started_at").toInstant(),
                r.getString("status"), r.getString("customer_id"), r.getString("subscription_id"), r.getLong("period_end"), r.getLong("stripe_trial_end"), r.getBoolean("cancel_at_period_end"),
                r.getString("checkout_id"), r.getString("checkout_url"), r.getLong("checkout_expires"), r.getInt("checkout_attempt"),
                r.getString("stripe_account_id"), r.getBoolean("stripe_charges_enabled"),
                toInstant(r.getTimestamp("paused_since")), toInstant(r.getTimestamp("pause_notified_at")),
                toInstant(r.getTimestamp("warning_notified_at")), toInstant(r.getTimestamp("contract_ended_at")),
                toInstant(r.getTimestamp("closing_started_at")), toInstant(r.getTimestamp("closed_at")));
    }
    private static java.time.Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
    private static Timestamp toTimestamp(java.time.Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
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

    private static final java.util.regex.Pattern EMAIL_PATTERN =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static boolean isValidEmail(String email) {
        return email != null && !email.isBlank() && email.strip().length() <= 254
                && EMAIL_PATTERN.matcher(email.strip()).matches();
    }

    @Override public Optional<String> resolveOwnerEmail(String tenantId, String ownerUsername) {
        return transaction(source, c -> {
            String schema = "tenant_" + es.brasatech.fastbite.application.tenant.TenantRegistrationRules.tenantId(tenantId);
            try (var check = statement(c, "SELECT 1 FROM information_schema.tables WHERE LOWER(table_schema) = LOWER(?) AND LOWER(table_name) = 'fiscal_profile'", schema);
                    var rows = check.executeQuery()) {
                if (rows.next()) {
                    try (var q = statement(c, "SELECT email FROM " + schema + ".fiscal_profile WHERE id = 1");
                            var r = q.executeQuery()) {
                        if (r.next()) {
                            String fiscalEmail = r.getString("email");
                            if (isValidEmail(fiscalEmail)) return Optional.of(fiscalEmail.strip());
                        }
                    }
                }
            }
            try (var q = statement(c, "SELECT email FROM public.owner_setup_tokens WHERE tenant_id = ? AND email IS NOT NULL ORDER BY expires_at DESC", tenantId);
                    var r = q.executeQuery()) {
                while (r.next()) {
                    String tokenEmail = r.getString("email");
                    if (isValidEmail(tokenEmail)) return Optional.of(tokenEmail.strip());
                }
            }
            if (ownerUsername != null && !ownerUsername.isBlank()) {
                try (var q = statement(c, "SELECT email FROM public.owner_setup_tokens WHERE username = ? AND email IS NOT NULL ORDER BY expires_at DESC", ownerUsername);
                        var r = q.executeQuery()) {
                    while (r.next()) {
                        String tokenEmail = r.getString("email");
                        if (isValidEmail(tokenEmail)) return Optional.of(tokenEmail.strip());
                    }
                }
                if (isValidEmail(ownerUsername)) return Optional.of(ownerUsername.strip());
            }
            return Optional.empty();
        });
    }

    @Override public void closeLocationData(String tenantId, java.time.Instant now) {
        transaction(source, c -> {
            String cleanTenant = es.brasatech.fastbite.application.tenant.TenantRegistrationRules.tenantId(tenantId);
            String schema = "tenant_" + cleanTenant;
            try (var check = statement(c, "SELECT 1 FROM information_schema.tables WHERE LOWER(table_schema) = LOWER(?) AND LOWER(table_name) = 'restaurant_settings'", schema);
                    var rows = check.executeQuery()) {
                if (rows.next()) {
                    update(c, "DELETE FROM " + schema + ".restaurant_settings");
                }
            }
            update(c, "UPDATE public.tenant_locations SET custom_domain = NULL, stripe_account_id = NULL, stripe_charges_enabled = FALSE, stripe_details_submitted = FALSE WHERE tenant_id = ?", cleanTenant);
            if (update(c, "UPDATE public.tenant_lifecycle SET state = 'CLOSED', updated_at = ? WHERE tenant_id = ?", now.toEpochMilli(), cleanTenant) == 0) {
                String owner = null;
                try (var q = statement(c, "SELECT owner_username FROM public.tenant_locations WHERE tenant_id = ?", cleanTenant);
                        var r = q.executeQuery()) {
                    if (r.next()) owner = r.getString(1);
                }
                if (owner != null) {
                    update(c, "INSERT INTO public.tenant_lifecycle (tenant_id, operation_id, owner_username, state, updated_at) VALUES (?, ?, ?, 'CLOSED', ?)",
                            cleanTenant, "closed:" + cleanTenant, owner, now.toEpochMilli());
                }
            }
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
