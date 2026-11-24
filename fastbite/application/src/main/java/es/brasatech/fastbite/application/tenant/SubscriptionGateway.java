package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.domain.tenant.BillingAccount;

public interface SubscriptionGateway {
    record Checkout(String id, String url, long expires, String tenant, String subscriptionId, boolean complete, String billingKey, String attempt) { }
    record Subscription(String id, String customerId, String tenant, String owner, String billingKey,
            String status, long periodEnd, long trialEnd, boolean cancelAtPeriodEnd, boolean supportedPrice) { }
    Checkout createCheckout(BillingAccount account, String idempotencyKey);
    Checkout checkout(String id);
    Subscription subscription(String id);
    default void cancelImmediately(String subscriptionId) { }
    default Subscription cancelAtPeriodEnd(String subscriptionId) { return subscription(subscriptionId); }
    String portal(String customerId);
    boolean configured();
}
