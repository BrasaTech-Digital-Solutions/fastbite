package es.brasatech.fastbite.domain.tenant;

public record PlatformBillingSettings(
    long monthlyPriceCents,
    int trialDays,
    int freePlanCutBps
) {
    public static PlatformBillingSettings defaultSettings() {
        return new PlatformBillingSettings(SubscriptionPlan.MONTHLY_CENTS, SubscriptionPlan.TRIAL_DAYS, SubscriptionPlan.FREE_PLAN_APPLICATION_FEE_BPS);
    }
}
