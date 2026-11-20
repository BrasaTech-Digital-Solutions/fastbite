package es.brasatech.fastbite.service;

import com.stripe.model.Price;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import es.brasatech.fastbite.domain.tenant.BillingAccount;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StripeBillingGatewayTest {
    private Price price() {
        var price=new Price(); price.setId("price_restaurant"); price.setActive(true); price.setCurrency("eur"); price.setUnitAmount(4900L); price.setTaxBehavior("exclusive");
        var recurring=new Price.Recurring(); recurring.setInterval("month"); recurring.setIntervalCount(1L); price.setRecurring(recurring); return price;
    }
    private BillingAccount account() {
        return new BillingAccount("cafe","owner","key",Instant.now(),"LOCAL_TRIAL",null,null,0,0,false,null,null,0,0)
                .reserveCheckout(Instant.now().getEpochSecond()+23*3600);
    }
    @Test void createsRecurringCheckoutWithServerPriceAndDurableMetadata() throws Exception {
        try(var prices=mockStatic(Price.class);var sessions=mockStatic(Session.class)) {
            prices.when(()->Price.retrieve(eq("price_restaurant"),any(RequestOptions.class))).thenReturn(price());
            sessions.when(()->Session.create(any(SessionCreateParams.class),any(RequestOptions.class))).thenAnswer(call->{
                SessionCreateParams params=call.getArgument(0); RequestOptions options=call.getArgument(1);
                assertThat(params.getMode()).isEqualTo(SessionCreateParams.Mode.SUBSCRIPTION);
                assertThat(params.getLineItems()).hasSize(1);
                assertThat(params.getLineItems().getFirst().getPrice()).isEqualTo("price_restaurant");
                assertThat(params.getLineItems().getFirst().getQuantity()).isEqualTo(1);
                assertThat(params.getMetadata()).containsEntry("plan","RESTAURANT").containsEntry("billingKey","key").containsEntry("checkoutAttempt","1");
                assertThat(params.getSubscriptionData().getMetadata()).isEqualTo(params.getMetadata());
                assertThat(params.getSuccessUrl()).isEqualTo("https://fastbite.example/owner/billing/cafe?checkout=returned");
                assertThat(options.getIdempotencyKey()).isEqualTo("durable-attempt");
                assertThat(params.getSubscriptionData().getTrialPeriodDays()).isNull(); // Trial is local, purchase starts now.
                var session=new Session();session.setId("checkout");session.setUrl("https://checkout.stripe.com/example");session.setExpiresAt(params.getExpiresAt());return session;
            });
            var gateway=new StripeBillingGateway("unit-key","price_restaurant","https://fastbite.example",false);
            assertThat(gateway.createCheckout(account(),"durable-attempt").id()).isEqualTo("checkout");
        }
    }
    @Test void rejectsWrongAmountCurrencyRecurrenceAndTaxBehavior() {
        var p=price();assertThat(StripeBillingGateway.validPrice(p)).isTrue();
        p.setUnitAmount(2900L);assertThat(StripeBillingGateway.validPrice(p)).isFalse();
        p=price();p.setCurrency("usd");assertThat(StripeBillingGateway.validPrice(p)).isFalse();
        p=price();p.getRecurring().setInterval("year");assertThat(StripeBillingGateway.validPrice(p)).isFalse();
        p=price();p.setTaxBehavior("inclusive");assertThat(StripeBillingGateway.validPrice(p)).isFalse();
        assertThat(new StripeBillingGateway("","","https://fastbite.example",false).configured()).isFalse();
    }

    @Test void setsTrialEndWhenLocalTrialHasMoreThan48HoursRemaining() {
        Instant now = Instant.parse("2026-11-20T12:00:00Z");
        var gateway = new StripeBillingGateway("unit-key", "price_restaurant", "https://fastbite.example", false,
                Clock.fixed(now, ZoneOffset.UTC));

        // 10 days into 30-day trial (20 days remaining > 48h) -> trial_end preserved
        BillingAccount plentyLeft = new BillingAccount("cafe", "owner", "key", now.minus(10, ChronoUnit.DAYS),
                "LOCAL_TRIAL", null, null, 0, 0, false, null, null, 0, 0)
                .reserveCheckout(now.plusSeconds(23 * 3600).getEpochSecond());
        SessionCreateParams paramsPlenty = gateway.buildCheckoutParams(plentyLeft);
        assertThat(paramsPlenty.getSubscriptionData().getTrialEnd())
                .isEqualTo(plentyLeft.trialEndsAt().getEpochSecond());

        // 29 days into 30-day trial (24h remaining <= 48h) -> no trial_end
        BillingAccount under48h = new BillingAccount("cafe", "owner", "key", now.minus(29, ChronoUnit.DAYS),
                "LOCAL_TRIAL", null, null, 0, 0, false, null, null, 0, 0)
                .reserveCheckout(now.plusSeconds(23 * 3600).getEpochSecond());
        assertThat(gateway.buildCheckoutParams(under48h).getSubscriptionData().getTrialEnd()).isNull();

        // Trial not started yet (trialStartedAt == null) -> no trial_end
        BillingAccount notStarted = new BillingAccount("cafe", "owner", "key", null,
                "LOCAL_TRIAL", null, null, 0, 0, false, null, null, 0, 0)
                .reserveCheckout(now.plusSeconds(23 * 3600).getEpochSecond());
        assertThat(gateway.buildCheckoutParams(notStarted).getSubscriptionData().getTrialEnd()).isNull();

        // Non-trial (FREE) -> no trial_end
        BillingAccount freePlan = new BillingAccount("cafe", "owner", "key", now,
                "FREE", null, null, 0, 0, false, null, null, 0, 0)
                .reserveCheckout(now.plusSeconds(23 * 3600).getEpochSecond());
        assertThat(gateway.buildCheckoutParams(freePlan).getSubscriptionData().getTrialEnd()).isNull();
    }
}
