package es.brasatech.fastbite.domain.tenant;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public record BillingAccount(String tenantId, String owner, String billingKey, Instant trialStartedAt,
        String status, String customerId, String subscriptionId, long periodEnd, long stripeTrialEnd,
        boolean cancelAtPeriodEnd, String checkoutId, String checkoutUrl, long checkoutExpires, int attempt,
        String stripeAccountId, boolean stripeChargesEnabled,
        Instant pausedSince, Instant pauseNotifiedAt, Instant warningNotifiedAt, Instant contractEndedAt,
        Instant closingStartedAt, Instant closedAt) {
    public BillingAccount(String tenantId, String owner, String billingKey, Instant trialStartedAt,
            String status, String customerId, String subscriptionId, long periodEnd, long stripeTrialEnd,
            boolean cancelAtPeriodEnd, String checkoutId, String checkoutUrl, long checkoutExpires, int attempt,
            String stripeAccountId, boolean stripeChargesEnabled,
            Instant pausedSince, Instant pauseNotifiedAt, Instant warningNotifiedAt, Instant contractEndedAt, Instant closedAt) {
        this(tenantId, owner, billingKey, trialStartedAt, status, customerId, subscriptionId, periodEnd, stripeTrialEnd,
                cancelAtPeriodEnd, checkoutId, checkoutUrl, checkoutExpires, attempt, stripeAccountId, stripeChargesEnabled,
                pausedSince, pauseNotifiedAt, warningNotifiedAt, contractEndedAt, null, closedAt);
    }
    public BillingAccount(String tenantId, String owner, String billingKey, Instant trialStartedAt,
            String status, String customerId, String subscriptionId, long periodEnd, long stripeTrialEnd,
            boolean cancelAtPeriodEnd, String checkoutId, String checkoutUrl, long checkoutExpires, int attempt,
            String stripeAccountId, boolean stripeChargesEnabled) {
        this(tenantId, owner, billingKey, trialStartedAt, status, customerId, subscriptionId, periodEnd, stripeTrialEnd,
                cancelAtPeriodEnd, checkoutId, checkoutUrl, checkoutExpires, attempt, stripeAccountId, stripeChargesEnabled,
                null, null, null, null, null, null);
    }
    public BillingAccount(String tenantId, String owner, String billingKey, Instant trialStartedAt,
            String status, String customerId, String subscriptionId, long periodEnd, long stripeTrialEnd,
            boolean cancelAtPeriodEnd, String checkoutId, String checkoutUrl, long checkoutExpires, int attempt) {
        this(tenantId, owner, billingKey, trialStartedAt, status, customerId, subscriptionId, periodEnd, stripeTrialEnd,
                cancelAtPeriodEnd, checkoutId, checkoutUrl, checkoutExpires, attempt, null, false,
                null, null, null, null, null, null);
    }
    public Instant trialEndsAt() { return trialStartedAt.plus(SubscriptionPlan.TRIAL_DAYS, ChronoUnit.DAYS); }
    public boolean isFreePlan() { return "FREE".equalsIgnoreCase(status); }
    public boolean isClosed() { return closedAt != null; }
    public boolean isContractEnded() { return contractEndedAt != null; }
    public boolean dinerPaymentsReady() { return stripeAccountId != null && !stripeAccountId.isBlank() && stripeChargesEnabled; }
    public boolean hasActiveBilling(Instant now) {
        return switch (status) {
            case "LOCAL_TRIAL" -> now.isBefore(trialEndsAt());
            case "trialing" -> stripeTrialEnd > now.getEpochSecond();
            case "active" -> periodEnd > now.getEpochSecond();
            default -> false;
        };
    }
    public Instant computePausedSince(Instant now) {
        if (pausedSince != null) return pausedSince;
        if ("LOCAL_TRIAL".equals(status) && !now.isBefore(trialEndsAt())) return trialEndsAt();
        if ("trialing".equals(status) && stripeTrialEnd > 0 && stripeTrialEnd <= now.getEpochSecond()) return Instant.ofEpochSecond(stripeTrialEnd);
        if (periodEnd > 0 && periodEnd <= now.getEpochSecond()) return Instant.ofEpochSecond(periodEnd);
        return now;
    }
    public boolean canTakeOrders(Instant now) { return canTakeOrders(now, false); }
    public boolean canTakeOrders(Instant now, boolean production) {
        if (closedAt != null || contractEndedAt != null) return false;
        return switch (status) {
            case "FREE", "free" -> !production || dinerPaymentsReady();
            case "LOCAL_TRIAL" -> now.isBefore(trialEndsAt());
            case "trialing" -> stripeTrialEnd > now.getEpochSecond();
            case "active" -> periodEnd > now.getEpochSecond();
            default -> false;
        };
    }
    public String displayStatus() {
        if (closedAt != null) {
            return "Closed on " + java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")
                    .withZone(java.time.ZoneId.of("Europe/Madrid")).format(closedAt);
        }
        if (contractEndedAt != null) return "Contract ended";
        if ("FREE".equalsIgnoreCase(status)) return dinerPaymentsReady() ? "Free Plan (0€/mo)" : "Free Plan · Connect payments";
        if ("LOCAL_TRIAL".equals(status)) return Instant.now().isBefore(trialEndsAt()) ? "Free trial" : "Trial ended";
        return status.replace('_', ' ') + (cancelAtPeriodEnd ? " (ends at period close)" : "");
    }
    public BillingAccount reserveCheckout(long deadline) {
        return new BillingAccount(tenantId, owner, billingKey, trialStartedAt, status, customerId, subscriptionId,
                periodEnd, stripeTrialEnd, cancelAtPeriodEnd, "pending:" + billingKey + ":" + (attempt + 1), null, deadline, attempt + 1,
                stripeAccountId, stripeChargesEnabled, pausedSince, pauseNotifiedAt, warningNotifiedAt, contractEndedAt, closingStartedAt, closedAt);
    }
    public BillingAccount checkout(String id, String url, long expires) {
        return new BillingAccount(tenantId, owner, billingKey, trialStartedAt, status, customerId, subscriptionId,
                periodEnd, stripeTrialEnd, cancelAtPeriodEnd, id, url, expires, attempt, stripeAccountId, stripeChargesEnabled,
                pausedSince, pauseNotifiedAt, warningNotifiedAt, contractEndedAt, closingStartedAt, closedAt);
    }
    public BillingAccount subscribed(String customer, String subscription, String state, long end, long trialEnd, boolean cancel) {
        return new BillingAccount(tenantId, owner, billingKey, trialStartedAt, state, customer, subscription,
                end, trialEnd, cancel, checkoutId, checkoutUrl, checkoutExpires, attempt, stripeAccountId, stripeChargesEnabled,
                pausedSince, pauseNotifiedAt, warningNotifiedAt, contractEndedAt, closingStartedAt, closedAt);
    }
    public BillingAccount withLifecycle(Instant pausedSince, Instant pauseNotifiedAt, Instant warningNotifiedAt,
            Instant contractEndedAt, Instant closedAt) {
        return withLifecycle(pausedSince, pauseNotifiedAt, warningNotifiedAt, contractEndedAt, closingStartedAt, closedAt);
    }
    public BillingAccount withLifecycle(Instant pausedSince, Instant pauseNotifiedAt, Instant warningNotifiedAt,
            Instant contractEndedAt, Instant closingStartedAt, Instant closedAt) {
        return new BillingAccount(tenantId, owner, billingKey, trialStartedAt, status, customerId, subscriptionId,
                periodEnd, stripeTrialEnd, cancelAtPeriodEnd, checkoutId, checkoutUrl, checkoutExpires, attempt,
                stripeAccountId, stripeChargesEnabled, pausedSince, pauseNotifiedAt, warningNotifiedAt, contractEndedAt, closingStartedAt, closedAt);
    }
}
