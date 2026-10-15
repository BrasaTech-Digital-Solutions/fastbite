package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=es.brasatech.fastbite.jpa.TestConfig.class)
class BillingJdbcIntegrationTest {
    @Autowired TenantSignupService signup;
    @Autowired BillingPort billing;
    @Autowired JdbcTemplate jdbc;
    @Test void registrationAndRetryCreateOneTrialAndCanonicalPlan() {
        signup.registerTenant("billingtrial","billingowner","hash","Owner");
        var first=billing.find("billingtrial").orElseThrow();
        signup.registerTenant("billingtrial","billingowner","other-hash","Owner");
        assertThat(billing.find("billingtrial").orElseThrow().trialStartedAt()).isEqualTo(first.trialStartedAt());
        assertThat(jdbc.queryForObject("SELECT plan FROM public.tenant_locations WHERE tenant_id='billingtrial'",String.class)).isEqualTo("RESTAURANT");
        assertThatThrownBy(()->signup.registerAdditionalLocation("billingforged","billingowner","Enterprise"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void databaseLockSerializesCheckoutAcrossServiceInstances() throws Exception {
        signup.registerTenant("billingrace","billingraceowner","hash","Owner");
        var gateway=mock(SubscriptionGateway.class);
        when(gateway.configured()).thenReturn(true);
        var checkout=new SubscriptionGateway.Checkout("race-checkout","https://checkout.stripe.com/example",Instant.now().plusSeconds(3600).getEpochSecond(),"billingrace",null,false,null,null);
        when(gateway.createCheckout(any(),anyString())).thenAnswer(call->{Thread.sleep(60);return checkout;});
        when(gateway.checkout("race-checkout")).thenReturn(checkout);
        var first=new SubscriptionService(billing,gateway);var second=new SubscriptionService(billing,gateway);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->first.checkout("billingrace","billingraceowner"));
            var b=executor.submit(()->second.checkout("billingrace","billingraceowner"));
            assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
        }
        verify(gateway,times(1)).createCheckout(any(),anyString());
        assertThat(billing.find("billingrace").orElseThrow().attempt()).isEqualTo(1);
    }
    @Test void failedStripeCallKeepsReservationCommittedAndDoesNotPublishPaidStatus() {
        signup.registerTenant("billingfailure","billingfailureowner","hash","Owner");
        var gateway=mock(SubscriptionGateway.class);
        when(gateway.configured()).thenReturn(true);
        when(gateway.createCheckout(any(),anyString())).thenThrow(new IllegalStateException("Timeout"));
        var service=new SubscriptionService(billing,gateway);
        assertThatThrownBy(()->service.checkout("billingfailure","billingfailureowner")).isInstanceOf(IllegalStateException.class);
        var pending=billing.find("billingfailure").orElseThrow();
        assertThat(pending.checkoutId()).startsWith("pending:");
        assertThat(pending.status()).isEqualTo("LOCAL_TRIAL");
        assertThat(pending.subscriptionId()).isNull();
    }
    @Test void quoteIsDurableAndRepeatedRequestUpdatesContact() {
        billing.requestGroupQuote("quoteowner","first@example.test",3);
        billing.requestGroupQuote("quoteowner","updated@example.test",5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.group_quote_requests WHERE owner_username='quoteowner'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT locations FROM public.group_quote_requests WHERE owner_username='quoteowner'",Integer.class)).isEqualTo(5);
    }
    @Test void platformSettingsDefaultsAreReturned() {
        var initial = billing.getPlatformSettings();
        assertThat(initial.monthlyPriceCents()).isEqualTo(4900);
        assertThat(initial.trialDays()).isEqualTo(30);
        assertThat(initial.freePlanCutBps()).isEqualTo(250);
    }
    @Test void tenantPlanCanBeOverriddenDynamically() {
        signup.registerTenant("planoverride","planowner","hash","Owner");
        billing.updateTenantPlan("planoverride", "FREE");
        assertThat(jdbc.queryForObject("SELECT plan FROM public.tenant_locations WHERE tenant_id='planoverride'", String.class)).isEqualTo("FREE");
        assertThat(billing.find("planoverride").orElseThrow().status()).isEqualTo("FREE");
    }
}
