package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.domain.tenant.BillingAccount;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Set;

@Service
public class SubscriptionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SubscriptionService.class);
    private final BillingPort billing;
    private final SubscriptionGateway gateway;
    private final es.brasatech.fastbite.application.platform.PlatformEnvironment environment;
    private final es.brasatech.fastbite.application.platform.PlatformAdmins admins;
    private static final Set<String> STATES = Set.of("active", "trialing", "past_due", "unpaid", "canceled", "incomplete", "incomplete_expired", "paused");
    public SubscriptionService(BillingPort billing, SubscriptionGateway gateway,
            es.brasatech.fastbite.application.platform.PlatformEnvironment environment,
            es.brasatech.fastbite.application.platform.PlatformAdmins admins) {
        this.billing = java.util.Objects.requireNonNull(billing, "billing");
        this.gateway = java.util.Objects.requireNonNull(gateway, "gateway");
        this.environment = java.util.Objects.requireNonNull(environment, "environment");
        this.admins = java.util.Objects.requireNonNull(admins, "admins");
    }
    public BillingAccount account(String tenant, String owner) {
        BillingAccount account = billing.find(tenant).orElseThrow(() -> new IllegalArgumentException("Billing is not available for this location."));
        requireOwner(account, owner);
        return account;
    }
    public boolean configured() { return gateway.configured(); }
    public boolean canTakeOrders(String tenant) {
        return tenant != null && billing.find(tenant).map(a -> a.canTakeOrders(Instant.now(), environment.production())).orElse(false);
    }
    public boolean isFreePlan(String tenant) {
        return tenant != null && billing.find(tenant).map(BillingAccount::isFreePlan).orElse(false);
    }
    public boolean isClosed(String tenant) {
        return tenant != null && billing.find(tenant).map(BillingAccount::isClosed).orElse(false);
    }
    public String checkout(String tenant, String owner) {
        var existing = account(tenant, owner);
        if (existing.isClosed()) throw new IllegalArgumentException("This location is closed and its contract has ended.");
        if (!gateway.configured()) throw new IllegalArgumentException("Subscription checkout is not available yet. Contact the platform operator.");
        // Commit the attempt BEFORE calling Stripe. A timeout or process crash must not lose its identity.
        billing.updateLocked(tenant, account -> {
            requireOwner(account, owner);
            if (account.checkoutId() != null && account.checkoutId().startsWith("pending:")) return account;
            if (account.subscriptionId() != null) {
                var current = gateway.subscription(account.subscriptionId());
                if (!Set.of("canceled", "incomplete_expired").contains(current.status()))
                    throw new IllegalArgumentException("A subscription already exists. Use Manage billing to update it.");
            }
            if (account.checkoutId() != null) {
                var pending = gateway.checkout(account.checkoutId());
                if (pending.complete() && !pending.subscriptionId().equals(account.subscriptionId()))
                    throw new IllegalArgumentException("Your subscription is being confirmed. Refresh billing shortly.");
                if (!pending.complete() && pending.expires() > Instant.now().getEpochSecond() && pending.url() != null) return account;
            }
            return account.reserveCheckout(Instant.now().getEpochSecond() + 23 * 3600);
        });
        var ready = billing.updateLocked(tenant, account -> {
            requireOwner(account, owner);
            if (!account.checkoutId().startsWith("pending:")) return account;
            if (account.checkoutExpires() < Instant.now().getEpochSecond() + 31 * 60)
                throw new IllegalArgumentException("This checkout needs a billing review. Contact support before trying again.");
            var session = gateway.createCheckout(account, "fastbite-subscription-" + account.billingKey() + "-" + account.attempt());
            return account.checkout(session.id(), session.url(), session.expires());
        });
        return ready.checkoutUrl() != null ? ready.checkoutUrl() : "/owner/billing/" + tenant;
    }
    public String portal(String tenant, String owner) {
        var account = account(tenant, owner);
        if (account.customerId() == null) throw new IllegalArgumentException("Activate a subscription before managing billing.");
        return gateway.portal(account.customerId());
    }
    public void refreshCheckout(String checkoutId) {
        var session = gateway.checkout(checkoutId);
        if (!session.complete() || session.subscriptionId() == null || session.tenant() == null) return;
        if (billing.find(session.tenant()).isEmpty()) return;
        billing.updateLocked(session.tenant(), account -> {
            if (!checkoutId.equals(account.checkoutId())) {
                if (account.checkoutId() == null || !account.checkoutId().startsWith("pending:")
                        || !account.billingKey().equals(session.billingKey()) || !String.valueOf(account.attempt()).equals(session.attempt())) return account;
            }
            var linked = account.checkout(session.id(), session.url(), session.expires());
            return apply(linked, gateway.subscription(session.subscriptionId()));
        });
    }
    public void refreshSubscription(String subscriptionId) {
        var first = gateway.subscription(subscriptionId);
        if (first.tenant() == null || billing.find(first.tenant()).isEmpty()) return;
        billing.updateLocked(first.tenant(), account -> {
            if (!subscriptionId.equals(account.subscriptionId())) {
                if (account.checkoutId() == null || account.checkoutId().startsWith("pending:")) return account;
                var checkout = gateway.checkout(account.checkoutId());
                if (!checkout.complete() || !subscriptionId.equals(checkout.subscriptionId())) return account;
            }
            // Fetch inside the row lock: delayed events cannot overwrite a newer Stripe state.
            return apply(account, gateway.subscription(subscriptionId));
        });
    }
    public void refresh(String tenant, String owner) {
        var account = account(tenant, owner);
        if (account.checkoutId() != null && !account.checkoutId().startsWith("pending:")) refreshCheckout(account.checkoutId());
        account = account(tenant, owner);
        if (account.subscriptionId() != null) refreshSubscription(account.subscriptionId());
    }
    private BillingAccount apply(BillingAccount account, SubscriptionGateway.Subscription sub) {
        if (!account.tenantId().equals(sub.tenant()) || !account.owner().equals(sub.owner())
                || !account.billingKey().equals(sub.billingKey()) || !sub.supportedPrice()
                || !STATES.contains(sub.status()) || sub.customerId() == null
                || (account.customerId() != null && !account.customerId().equals(sub.customerId())))
            throw new IllegalArgumentException("Subscription does not match this restaurant and plan.");
        return account.subscribed(sub.customerId(), sub.id(), sub.status(), sub.periodEnd(), sub.trialEnd(), sub.cancelAtPeriodEnd());
    }
    private static void requireOwner(BillingAccount account, String owner) {
        if (owner == null || !owner.equals(account.owner())) throw new IllegalArgumentException("You do not own this location.");
    }
    public void requestGroupQuote(String owner, String email, int locations) {
        if (email == null || email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || locations < 3 || locations > 1000)
            throw new IllegalArgumentException("Enter a valid email and between 3 and 1000 locations.");
        billing.requestGroupQuote(owner, email, locations);
    }
    public es.brasatech.fastbite.domain.tenant.PlatformBillingSettings platformSettings() {
        return billing.getPlatformSettings();
    }
    public void updatePlatformSettings(es.brasatech.fastbite.domain.tenant.PlatformBillingSettings settings) {
        if (settings.monthlyPriceCents() < 0 || settings.trialDays() < 0 || settings.freePlanCutBps() < 0) {
            throw new IllegalArgumentException("Billing parameters cannot be negative.");
        }
        billing.updatePlatformSettings(settings);
    }
    public void updateTenantPlan(String tenantId, String owner, String plan) {
        var existing = account(tenantId, owner);
        if (existing.isClosed()) throw new IllegalArgumentException("This location is closed and its contract has ended.");
        billing.updateTenantPlan(tenantId, plan);
    }
    public java.util.List<BillingAccount> listAllAccounts(String admin) {
        if (!admins.isAdmin(admin)) throw new IllegalArgumentException("Only a platform administrator may view all trials.");
        return billing.findAll();
    }
    public BillingAccount extendTenantTrial(String tenantId, String admin, int extraDays) {
        if (!admins.isAdmin(admin)) throw new IllegalArgumentException("Only a platform administrator may extend a trial.");
        if (extraDays < 1 || extraDays > 180) throw new IllegalArgumentException("Trial extension must be between 1 and 180 days.");
        var existing = billing.find(tenantId).orElseThrow(() -> new IllegalArgumentException("Billing is not available for this location."));
        if (existing.isClosed()) throw new IllegalArgumentException("This location is closed and its contract has ended.");
        billing.extendTenantTrial(tenantId, extraDays);
        BillingAccount updated = billing.find(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Billing is not available for this location."));
        log.info("Platform admin '{}' extended trial for location '{}' by {} days (new trial end: {})",
                admin.trim(), tenantId, extraDays, updated.trialEndsAt());
        return updated;
    }
}
