package es.brasatech.fastbite.domain.tenant;

/** Stable identifiers, independent of Stripe price IDs and billing status. */
public enum SubscriptionPlan {
    FREE,
    RESTAURANT;

    public static final long MONTHLY_CENTS = 4900;
    public static final int TRIAL_DAYS = 30;

    /** 2.5% platform cut (250 basis points) on takeaway card orders for Free Plan tenants. */
    public static final int FREE_PLAN_APPLICATION_FEE_BPS = 250;
}

