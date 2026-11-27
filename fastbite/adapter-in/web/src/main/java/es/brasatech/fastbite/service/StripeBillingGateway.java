package es.brasatech.fastbite.service;

import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.SubscriptionCancelParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import es.brasatech.fastbite.application.tenant.SubscriptionGateway;
import es.brasatech.fastbite.domain.tenant.BillingAccount;
import es.brasatech.fastbite.domain.tenant.SubscriptionPlan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

@Service
public class StripeBillingGateway implements SubscriptionGateway {
    private static final long MIN_STRIPE_TRIAL_REMAINING_HOURS = 48;
    private final String key, priceId, publicUrl;
    private final boolean automaticTax;
    private final Clock clock;

    public StripeBillingGateway(String key, String priceId, String publicUrl, boolean automaticTax) {
        this(key, priceId, publicUrl, automaticTax, Clock.systemUTC());
    }

    @Autowired
    public StripeBillingGateway(@Value("${stripe.secret.key:}") String key,
            @Value("${stripe.restaurant-price-id:}") String priceId,
            @Value("${fastbite.public-url:http://localhost:8080}") String publicUrl,
            @Value("${stripe.automatic-tax:false}") boolean automaticTax,
            Clock clock) {
        this.key=key; this.priceId=priceId; this.publicUrl=publicUrl.replaceAll("/+$", ""); this.automaticTax=automaticTax;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }
    @Override public boolean configured() { return !key.isBlank() && !priceId.isBlank(); }
    private RequestOptions options(String idempotencyKey) {
        if (!configured()) throw new IllegalArgumentException("Subscription checkout is not available yet. Your trial remains available.");
        return RequestOptions.builder().setApiKey(key).setIdempotencyKey(idempotencyKey).setConnectTimeout(5000).setReadTimeout(15000).build();
    }
    @Override public Checkout createCheckout(BillingAccount account, String idempotencyKey) {
        try {
            Price price = Price.retrieve(priceId, options(null));
            if (!validPrice(price) || !Boolean.TRUE.equals(price.getActive())) throw new IllegalArgumentException("The Restaurant price must be EUR 49 per month, exclusive of tax.");
            return map(Session.create(buildCheckoutParams(account), options(idempotencyKey)));
        } catch (StripeException e) { throw unavailable(); }
    }

    SessionCreateParams buildCheckoutParams(BillingAccount account) {
        Map<String,String> metadata = Map.of("type", "PLATFORM_SUBSCRIPTION", "plan", "RESTAURANT",
                "tenantId", account.tenantId(), "ownerUsername", account.owner(), "billingKey", account.billingKey(), "checkoutAttempt", String.valueOf(account.attempt()));
        SessionCreateParams.SubscriptionData.Builder subscriptionData = SessionCreateParams.SubscriptionData.builder()
                .putAllMetadata(metadata);
        if ("LOCAL_TRIAL".equals(account.status()) && account.trialStartedAt() != null) {
            Instant trialEndsAt = account.trialEndsAt();
            if (trialEndsAt != null && trialEndsAt.isAfter(clock.instant().plus(MIN_STRIPE_TRIAL_REMAINING_HOURS, ChronoUnit.HOURS))) {
                subscriptionData.setTrialEnd(trialEndsAt.getEpochSecond());
            }
        }
        var params = SessionCreateParams.builder().setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setSuccessUrl(publicUrl + "/owner/billing/" + account.tenantId() + "?checkout=returned")
                .setCancelUrl(publicUrl + "/owner/billing/" + account.tenantId() + "?checkout=canceled")
                .setExpiresAt(account.checkoutExpires()).setClientReferenceId(account.tenantId()).putAllMetadata(metadata)
                .setSubscriptionData(subscriptionData.build())
                .addLineItem(SessionCreateParams.LineItem.builder().setPrice(priceId).setQuantity(1L).build())
                .setAllowPromotionCodes(true)
                .setAutomaticTax(SessionCreateParams.AutomaticTax.builder().setEnabled(automaticTax).build())
                .setBillingAddressCollection(SessionCreateParams.BillingAddressCollection.REQUIRED)
                .setTaxIdCollection(SessionCreateParams.TaxIdCollection.builder().setEnabled(true).build());
        // Each location has its own customer: its portal cannot expose another owner's subscriptions.
        if (account.customerId() != null) params.setCustomer(account.customerId());
        return params.build();
    }
    @Override public Checkout checkout(String id) {
        try { return map(Session.retrieve(id, options(null))); } catch (StripeException e) { throw unavailable(); }
    }
    private Checkout map(Session s) {
        return new Checkout(s.getId(), s.getUrl(), s.getExpiresAt() == null ? 0 : s.getExpiresAt(),
                s.getMetadata() == null ? null : s.getMetadata().get("tenantId"), s.getSubscription(),
                "subscription".equals(s.getMode()) && "complete".equals(s.getStatus()),
                s.getMetadata() == null ? null : s.getMetadata().get("billingKey"),
                s.getMetadata() == null ? null : s.getMetadata().get("checkoutAttempt"));
    }
    @Override public Subscription subscription(String id) {
        try {
            return mapSubscription(com.stripe.model.Subscription.retrieve(id, options(null)));
        } catch (StripeException e) { throw unavailable(); }
    }
    @Override public void cancelImmediately(String subscriptionId) {
        try {
            var s = com.stripe.model.Subscription.retrieve(subscriptionId, options(null));
            if (!Set.of("canceled", "incomplete_expired").contains(s.getStatus())) {
                s.cancel(SubscriptionCancelParams.builder().build(), options("fastbite-cancel-now-" + subscriptionId));
            }
        } catch (StripeException e) { throw unavailable(); }
    }
    @Override public Subscription cancelAtPeriodEnd(String subscriptionId) {
        try {
            var s = com.stripe.model.Subscription.retrieve(subscriptionId, options(null));
            if (!Set.of("canceled", "incomplete_expired").contains(s.getStatus())
                    && !Boolean.TRUE.equals(s.getCancelAtPeriodEnd())) {
                var params = SubscriptionUpdateParams.builder()
                        .setCancelAtPeriodEnd(true)
                        .build();
                s = s.update(params, options(null));
            }
            return mapSubscription(s);
        } catch (StripeException e) { throw unavailable(); }
    }
    @Override public Subscription resumeAtPeriodEnd(String subscriptionId) {
        try {
            var s = com.stripe.model.Subscription.retrieve(subscriptionId, options(null));
            if (!Set.of("canceled", "incomplete_expired").contains(s.getStatus())
                    && Boolean.TRUE.equals(s.getCancelAtPeriodEnd())) {
                var params = SubscriptionUpdateParams.builder()
                        .setCancelAtPeriodEnd(false)
                        .build();
                s = s.update(params, options(null));
            }
            return mapSubscription(s);
        } catch (StripeException e) { throw unavailable(); }
    }
    private Subscription mapSubscription(com.stripe.model.Subscription s) {
        var m = s.getMetadata() == null ? Map.<String,String>of() : s.getMetadata();
        var items = s.getItems().getData();
        boolean supported = items.size() == 1 && Long.valueOf(1).equals(items.getFirst().getQuantity())
                && priceId.equals(items.getFirst().getPrice().getId()) && validPrice(items.getFirst().getPrice())
                && "PLATFORM_SUBSCRIPTION".equals(m.get("type")) && "RESTAURANT".equals(m.get("plan"));
        return new Subscription(s.getId(), s.getCustomer(), m.get("tenantId"), m.get("ownerUsername"), m.get("billingKey"),
                s.getStatus(), s.getCurrentPeriodEnd() == null ? 0 : s.getCurrentPeriodEnd(), s.getTrialEnd() == null ? 0 : s.getTrialEnd(),
                Boolean.TRUE.equals(s.getCancelAtPeriodEnd()), supported);
    }
    static boolean validPrice(Price p) {
        return p != null && "eur".equals(p.getCurrency()) && Long.valueOf(SubscriptionPlan.MONTHLY_CENTS).equals(p.getUnitAmount())
                && "exclusive".equals(p.getTaxBehavior()) && p.getRecurring() != null
                && "month".equals(p.getRecurring().getInterval()) && Long.valueOf(1).equals(p.getRecurring().getIntervalCount());
    }
    @Override public String portal(String customerId) {
        try {
            return com.stripe.model.billingportal.Session.create(com.stripe.param.billingportal.SessionCreateParams.builder()
                    .setCustomer(customerId).setReturnUrl(publicUrl + "/owner/console").build(), options(null)).getUrl();
        } catch (StripeException e) { throw unavailable(); }
    }
    private IllegalStateException unavailable() { return new IllegalStateException("Billing is temporarily unavailable. Please try again shortly."); }
}
