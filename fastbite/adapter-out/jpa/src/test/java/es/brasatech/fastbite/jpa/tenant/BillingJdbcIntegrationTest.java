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
        var env=new es.brasatech.fastbite.application.platform.PlatformEnvironment("sandbox");
        var admins=new es.brasatech.fastbite.application.platform.PlatformAdmins("");
        var first=new SubscriptionService(billing,gateway,env,admins);var second=new SubscriptionService(billing,gateway,env,admins);
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
        var service=new SubscriptionService(billing,gateway,new es.brasatech.fastbite.application.platform.PlatformEnvironment("sandbox"),new es.brasatech.fastbite.application.platform.PlatformAdmins(""));
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
    @Test void tenantPlanCanBeOverriddenDynamicallyAndSwitchingToFreeEnablesTakeaway() {
        signup.registerTenant("planoverride","planowner","hash","Owner");
        jdbc.execute("INSERT INTO tenant_planoverride.restaurant_settings (id, dine_in, takeaway, kds_yellow_minutes, kds_red_minutes) VALUES (1, TRUE, FALSE, 0, 0)");
        billing.updateTenantPlan("planoverride", "FREE");
        assertThat(jdbc.queryForObject("SELECT plan FROM public.tenant_locations WHERE tenant_id='planoverride'", String.class)).isEqualTo("FREE");
        var freeAccount = billing.find("planoverride").orElseThrow();
        assertThat(freeAccount.status()).isEqualTo("FREE");
        assertThat(freeAccount.dinerPaymentsReady()).isFalse();
        assertThat(jdbc.queryForObject("SELECT takeaway FROM tenant_planoverride.restaurant_settings WHERE id = 1", Boolean.class)).isTrue();

        jdbc.update("UPDATE public.tenant_locations SET stripe_account_id='acct_live', stripe_charges_enabled=TRUE WHERE tenant_id='planoverride'");
        assertThat(billing.find("planoverride").orElseThrow().dinerPaymentsReady()).isTrue();

        // Switching back to RESTAURANT restores LOCAL_TRIAL while keeping original trial_started_at
        Instant originalTrialStart = freeAccount.trialStartedAt();
        billing.updateTenantPlan("planoverride", "RESTAURANT");
        var restoredTrial = billing.find("planoverride").orElseThrow();
        assertThat(restoredTrial.status()).isEqualTo("LOCAL_TRIAL");
        assertThat(restoredTrial.trialStartedAt()).isEqualTo(originalTrialStart);
        assertThat(restoredTrial.canTakeOrders(Instant.now(), true)).isTrue();

        // If the original trial had already run out before switching FREE -> RESTAURANT, orders stay paused until they subscribe
        billing.updateTenantPlan("planoverride", "FREE");
        Instant expiredStart = Instant.now().minus(java.time.Duration.ofDays(31));
        jdbc.update("UPDATE public.tenant_billing SET trial_started_at = ? WHERE tenant_id = 'planoverride'",
                java.sql.Timestamp.from(expiredStart));
        billing.updateTenantPlan("planoverride", "RESTAURANT");
        var expiredTrial = billing.find("planoverride").orElseThrow();
        assertThat(expiredTrial.status()).isEqualTo("LOCAL_TRIAL");
        assertThat(expiredTrial.canTakeOrders(Instant.now(), true)).isFalse();
    }

    @Test void contractLifecycleColumnsOwnerEmailResolutionAndPermanentClosure() {
        signup.registerTenant("lifecycleloc", "owner@bistro.test", "hash", "Owner");
        // Group quote email must never be used for contract lifecycle notices
        billing.requestGroupQuote("owner@bistro.test", "quote-only@bistro.test", 4);

        // 1. Falls back to owner username when it is an email address
        assertThat(billing.resolveOwnerEmail("lifecycleloc", "owner@bistro.test")).contains("owner@bistro.test");

        // 2. owner_setup_tokens email takes precedence over username
        jdbc.update("INSERT INTO public.owner_setup_tokens (checkout_id, tenant_id, username, email, user_id, token_hash, expires_at, completed) VALUES ('chk_lc', 'lifecycleloc', 'owner@bistro.test', 'setup@bistro.test', 'u1', NULL, 1000, TRUE)");
        assertThat(billing.resolveOwnerEmail("lifecycleloc", "owner@bistro.test")).contains("setup@bistro.test");

        // 3. fiscal_profile.email takes first priority
        jdbc.update("INSERT INTO tenant_lifecycleloc.fiscal_profile (id, legal_name, tax_id, address_line, postal_code, city, province, email, series_prefix, default_vat_rate) VALUES (1, 'Bistro SL', 'B12345678', 'Calle 1', '28001', 'Madrid', 'Madrid', 'fiscal@bistro.test', 'BIS', 10.00)");
        assertThat(billing.resolveOwnerEmail("lifecycleloc", "owner@bistro.test")).contains("fiscal@bistro.test");

        // 4. Lifecycle timestamps persist and round-trip through updateLocked and findAll
        Instant t0 = Instant.parse("2026-10-01T10:00:00Z");
        Instant t1 = Instant.parse("2026-10-01T11:00:00Z");
        Instant t2 = Instant.parse("2026-10-24T11:00:00Z");
        Instant t3 = Instant.parse("2026-10-31T11:00:00Z");
        billing.updateLocked("lifecycleloc", a -> a.withLifecycle(t0, t1, t2, t3, null));
        var ended = billing.find("lifecycleloc").orElseThrow();
        assertThat(ended.pausedSince()).isEqualTo(t0);
        assertThat(ended.pauseNotifiedAt()).isEqualTo(t1);
        assertThat(ended.warningNotifiedAt()).isEqualTo(t2);
        assertThat(ended.contractEndedAt()).isEqualTo(t3);
        assertThat(ended.closedAt()).isNull();
        assertThat(ended.canTakeOrders(t3, true)).isFalse();

        // 5. Closing location deletes restaurant_settings, releases custom_domain & stripe_account_id,
        //    marks tenant_lifecycle CLOSED, and permanently blocks tenantId reuse (even by the same owner).
        jdbc.execute("INSERT INTO tenant_lifecycleloc.restaurant_settings (id, dine_in, takeaway, kds_yellow_minutes, kds_red_minutes) VALUES (1, TRUE, TRUE, 5, 10)");
        jdbc.update("UPDATE public.tenant_locations SET custom_domain='bistro.example.com', stripe_account_id='acct_bistro', stripe_charges_enabled=TRUE WHERE tenant_id='lifecycleloc'");
        Instant closingStartedAt = Instant.parse("2026-11-30T10:59:50Z");
        Instant closedAt = Instant.parse("2026-11-30T11:00:00Z");
        billing.updateLocked("lifecycleloc", a -> a.withLifecycle(t0, t1, t2, t3, closingStartedAt, null));
        assertThat(billing.find("lifecycleloc").orElseThrow().closingStartedAt()).isEqualTo(closingStartedAt);
        billing.closeLocationData("lifecycleloc", closedAt);
        billing.updateLocked("lifecycleloc", a -> a.withLifecycle(t0, t1, t2, t3, closingStartedAt, closedAt));

        var closed = billing.find("lifecycleloc").orElseThrow();
        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.closingStartedAt()).isEqualTo(closingStartedAt);
        assertThat(closed.displayStatus()).isEqualTo("Closed on 30/11/2026");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_lifecycleloc.restaurant_settings", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT custom_domain FROM public.tenant_locations WHERE tenant_id='lifecycleloc'", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT stripe_account_id FROM public.tenant_locations WHERE tenant_id='lifecycleloc'", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT state FROM public.tenant_lifecycle WHERE tenant_id='lifecycleloc'", String.class)).isEqualTo("CLOSED");
        // Fiscal profile remains intact
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_lifecycleloc.fiscal_profile", Integer.class)).isEqualTo(1);

        // Neither another owner nor the same owner can ever reuse the closed tenantId
        assertThatThrownBy(() -> signup.registerTenant("lifecycleloc", "newowner", "hash", "New Owner"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signup.registerAdditionalLocation("lifecycleloc", "owner@bistro.test", "RESTAURANT"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
