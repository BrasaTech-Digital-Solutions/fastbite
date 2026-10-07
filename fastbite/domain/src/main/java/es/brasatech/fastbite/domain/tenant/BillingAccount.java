package es.brasatech.fastbite.domain.tenant;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public record BillingAccount(String tenantId, String owner, String billingKey, Instant trialStartedAt,
        String status, String customerId, String subscriptionId, long periodEnd, long stripeTrialEnd,
        boolean cancelAtPeriodEnd, String checkoutId, String checkoutUrl, long checkoutExpires, int attempt) {
    public Instant trialEndsAt() { return trialStartedAt.plus(SubscriptionPlan.TRIAL_DAYS, ChronoUnit.DAYS); }
    public boolean canTakeOrders(Instant now) {
        return switch (status) {
            case "FREE", "free" -> true;
            case "LOCAL_TRIAL" -> now.isBefore(trialEndsAt());
            case "trialing" -> stripeTrialEnd > now.getEpochSecond();
            case "active" -> periodEnd > now.getEpochSecond();
            default -> false;
        };
    }
    public String displayStatus() {
        if ("FREE".equalsIgnoreCase(status)) return "Free Plan (0€/mo)";
        if ("LOCAL_TRIAL".equals(status)) return Instant.now().isBefore(trialEndsAt()) ? "Free trial" : "Trial ended";
        return status.replace('_', ' ') + (cancelAtPeriodEnd ? " (ends at period close)" : "");
    }
    public BillingAccount reserveCheckout(long deadline) {
        return new BillingAccount(tenantId, owner, billingKey, trialStartedAt, status, customerId, subscriptionId,
                periodEnd, stripeTrialEnd, cancelAtPeriodEnd, "pending:" + billingKey + ":" + (attempt + 1), null, deadline, attempt + 1);
    }
    public BillingAccount checkout(String id, String url, long expires) {
        return new BillingAccount(tenantId, owner, billingKey, trialStartedAt, status, customerId, subscriptionId,
                periodEnd, stripeTrialEnd, cancelAtPeriodEnd, id, url, expires, attempt);
    }
    public BillingAccount subscribed(String customer, String subscription, String state, long end, long trialEnd, boolean cancel) {
        return new BillingAccount(tenantId, owner, billingKey, trialStartedAt, state, customer, subscription,
                end, trialEnd, cancel, checkoutId, checkoutUrl, checkoutExpires, attempt);
    }
}
