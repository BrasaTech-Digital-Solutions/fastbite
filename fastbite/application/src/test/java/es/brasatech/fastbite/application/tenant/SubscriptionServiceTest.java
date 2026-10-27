package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.domain.tenant.BillingAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubscriptionServiceTest {
    BillingPort port = mock(BillingPort.class);
    SubscriptionGateway gateway = mock(SubscriptionGateway.class);
    es.brasatech.fastbite.application.platform.PlatformAdmins admins = new es.brasatech.fastbite.application.platform.PlatformAdmins("brasatech");
    SubscriptionService service = new SubscriptionService(port, gateway, new es.brasatech.fastbite.application.platform.PlatformEnvironment("sandbox"), admins);
    BillingAccount account;
    @BeforeEach void setup() {
        when(gateway.configured()).thenReturn(true);
        account = new BillingAccount("cafe", "owner", "billing-key", Instant.now(), "LOCAL_TRIAL", null, null, 0, 0, false, null, null, 0, 0);
        when(port.find("cafe")).thenAnswer(call -> Optional.of(account));
        when(port.updateLocked(eq("cafe"), any())).thenAnswer(call -> {
            BillingAccount updated = call.<java.util.function.UnaryOperator<BillingAccount>>getArgument(1).apply(account);
            account = updated;
            return updated;
        });
    }
    private SubscriptionGateway.Checkout checkout(boolean complete) {
        return new SubscriptionGateway.Checkout("checkout", complete ? null : "https://checkout.stripe.com/example", Instant.now().getEpochSecond()+3600,
                "cafe", complete ? "subscription" : null, complete, "billing-key", "1");
    }
    private SubscriptionGateway.Subscription subscription(String status, boolean supported) {
        return new SubscriptionGateway.Subscription("subscription", "customer", "cafe", "owner", "billing-key", status,
                Instant.now().plus(30,ChronoUnit.DAYS).getEpochSecond(), 0, false, supported);
    }
    @Test void trialExpiresAtThirtyDaysAndUnknownRestaurantsCannotOrder() {
        assertThat(service.canTakeOrders("cafe")).isTrue();
        assertThat(account.canTakeOrders(account.trialEndsAt())).isFalse();
        assertThat(service.canTakeOrders("unknown")).isFalse();
        assertThat(service.canTakeOrders(null)).isFalse();
    }
    @Test void onlyOwnerCanViewStartOrManageBilling() {
        assertThatThrownBy(()->service.account("cafe","other")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.checkout("cafe","other")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.portal("cafe","other")).isInstanceOf(IllegalArgumentException.class);
        verify(gateway,never()).createCheckout(any(),anyString());
    }
    @Test void repeatedClicksReuseCheckoutAndPersistAttemptBeforeNetworkCall() {
        when(gateway.createCheckout(any(),anyString())).thenAnswer(call -> {
            assertThat(account.checkoutId()).startsWith("pending:");
            assertThat(account.attempt()).isEqualTo(1);
            return checkout(false);
        });
        when(gateway.checkout("checkout")).thenReturn(checkout(false));
        assertThat(service.checkout("cafe","owner")).startsWith("https://checkout.stripe.com/");
        service.checkout("cafe","owner");
        verify(gateway,times(1)).createCheckout(any(),eq("fastbite-subscription-billing-key-1"));
        assertThat(account.attempt()).isEqualTo(1);
    }
    @Test void timeoutRetriesSameDurableAttemptAndDoesNotResetTrial() {
        Instant start=account.trialStartedAt();
        when(gateway.createCheckout(any(),anyString())).thenThrow(new IllegalStateException("Timeout")).thenReturn(checkout(false));
        assertThatThrownBy(()->service.checkout("cafe","owner")).isInstanceOf(IllegalStateException.class);
        assertThat(account.checkoutId()).startsWith("pending:");
        service.checkout("cafe","owner");
        verify(gateway,times(2)).createCheckout(any(),eq("fastbite-subscription-billing-key-1"));
        assertThat(account.trialStartedAt()).isEqualTo(start);
    }
    @Test void expiredUnresolvedAttemptCannotCreateAnotherCharge() {
        account=account.reserveCheckout(Instant.now().minusSeconds(1).getEpochSecond());
        assertThatThrownBy(()->service.checkout("cafe","owner")).isInstanceOf(IllegalArgumentException.class);
        verify(gateway,never()).createCheckout(any(),anyString());
    }
    @Test void verifiedCheckoutActivatesAndDuplicateEventsReadCurrentState() {
        account=account.reserveCheckout(Instant.now().plusSeconds(3600).getEpochSecond());
        when(gateway.checkout("checkout")).thenReturn(checkout(true));
        when(gateway.subscription("subscription")).thenReturn(subscription("active",true));
        service.refreshCheckout("checkout"); // Recovers even if the process crashed before storing the Stripe session ID.
        assertThat(account.status()).isEqualTo("active");
        assertThat(account.subscriptionId()).isEqualTo("subscription");
        assertThat(service.canTakeOrders("cafe")).isTrue();
        when(gateway.subscription("subscription")).thenReturn(subscription("past_due",true));
        service.refreshSubscription("subscription");
        service.refreshCheckout("checkout");
        assertThat(account.status()).isEqualTo("past_due");
        assertThat(service.canTakeOrders("cafe")).isFalse();
        when(gateway.subscription("subscription")).thenReturn(subscription("active",true));
        service.refreshSubscription("subscription");
        assertThat(service.canTakeOrders("cafe")).isTrue();
    }
    @Test void mismatchedPriceOrOwnerCannotActivate() {
        account=account.reserveCheckout(Instant.now().plusSeconds(3600).getEpochSecond());
        when(gateway.checkout("checkout")).thenReturn(checkout(true));
        when(gateway.subscription("subscription")).thenReturn(subscription("active",false));
        assertThatThrownBy(()->service.refreshCheckout("checkout")).isInstanceOf(IllegalArgumentException.class);
        assertThat(account.status()).isEqualTo("LOCAL_TRIAL");
        when(gateway.subscription("subscription")).thenReturn(new SubscriptionGateway.Subscription("subscription","customer","cafe","other","billing-key","active",9999999999L,0,false,true));
        assertThatThrownBy(()->service.refreshCheckout("checkout")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cancelledOrUnpaidSubscriptionsCannotFallBackToUnusedTrial() {
        for(String state: new String[]{"canceled","unpaid","incomplete","incomplete_expired","paused","past_due"}) {
            account=account.subscribed("customer","subscription",state,Instant.now().plusSeconds(3600).getEpochSecond(),0,false);
            assertThat(service.canTakeOrders("cafe")).as(state).isFalse();
        }
        account=account.subscribed("customer","subscription","active",Instant.now().plusSeconds(3600).getEpochSecond(),0,true);
        assertThat(service.canTakeOrders("cafe")).isTrue(); // Scheduled cancellation keeps the paid period.
        assertThat(account.canTakeOrders(Instant.ofEpochSecond(account.periodEnd()))).isFalse();
    }
    @Test void activeSubscriptionUsesPortalInsteadOfAnotherCheckout() {
        account=account.subscribed("customer","subscription","active",9999999999L,0,false);
        when(gateway.subscription("subscription")).thenReturn(subscription("active",true));
        assertThatThrownBy(()->service.checkout("cafe","owner")).isInstanceOf(IllegalArgumentException.class);
        when(gateway.portal("customer")).thenReturn("https://billing.stripe.com/example");
        assertThat(service.portal("cafe","owner")).isEqualTo("https://billing.stripe.com/example");
        verify(gateway,never()).createCheckout(any(),anyString());
    }
    @Test void quoteRequiresRealContactAndThreeLocations() {
        assertThatThrownBy(()->service.requestGroupQuote("owner","invalid",3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.requestGroupQuote("owner","a@example.test",2)).isInstanceOf(IllegalArgumentException.class);
        service.requestGroupQuote("owner","a@example.test",3);
        verify(port).requestGroupQuote("owner","a@example.test",3);
    }
    @Test void webhookCompletingBetweenCheckoutPhasesReturnsBillingInsteadOfNullUrl() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(port.updateLocked(eq("cafe"),any())).thenAnswer(call->{
            if(calls.incrementAndGet()==2) account=account.checkout("checkout",null,9999999999L).subscribed("customer","subscription","active",9999999999L,0,false);
            account=call.<java.util.function.UnaryOperator<BillingAccount>>getArgument(1).apply(account);return account;
        });
        assertThat(service.checkout("cafe","owner")).isEqualTo("/owner/billing/cafe");
        verify(gateway,never()).createCheckout(any(),anyString());
    }
    @Test void platformSettingsDelegatesToPort() {
        var settings = new es.brasatech.fastbite.domain.tenant.PlatformBillingSettings(5000, 20, 200);
        when(port.getPlatformSettings()).thenReturn(settings);
        assertThat(service.platformSettings()).isEqualTo(settings);
        service.updatePlatformSettings(settings);
        verify(port).updatePlatformSettings(settings);
        assertThatThrownBy(() -> service.updatePlatformSettings(new es.brasatech.fastbite.domain.tenant.PlatformBillingSettings(-1, 20, 200)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void freePlanInProductionPausesOrdersUntilStripeChargesAreEnabledWhileSandboxStaysUsable() {
        var prodService = new SubscriptionService(port, gateway, new es.brasatech.fastbite.application.platform.PlatformEnvironment("production"), admins);
        var sandboxService = new SubscriptionService(port, gateway, new es.brasatech.fastbite.application.platform.PlatformEnvironment("sandbox"), admins);

        account = new BillingAccount("cafe", "owner", "billing-key", Instant.now(), "FREE", null, null, 0, 0, false, null, null, 0, 0, null, false);
        assertThat(prodService.canTakeOrders("cafe")).isFalse();
        assertThat(sandboxService.canTakeOrders("cafe")).isTrue();
        assertThat(account.displayStatus()).isEqualTo("Free Plan · Connect payments");

        account = new BillingAccount("cafe", "owner", "billing-key", Instant.now(), "FREE", null, null, 0, 0, false, null, null, 0, 0, "acct_123", false);
        assertThat(prodService.canTakeOrders("cafe")).isFalse();
        assertThat(sandboxService.canTakeOrders("cafe")).isTrue();

        account = new BillingAccount("cafe", "owner", "billing-key", Instant.now(), "FREE", null, null, 0, 0, false, null, null, 0, 0, "acct_123", true);
        assertThat(prodService.canTakeOrders("cafe")).isTrue();
        assertThat(sandboxService.canTakeOrders("cafe")).isTrue();
        assertThat(account.displayStatus()).isEqualTo("Free Plan (0€/mo)");
    }
    @Test void onlyOwnerCanSwitchPlanAndOnlyPlatformAdminCanExtendTrial() {
        assertThatThrownBy(() -> service.updateTenantPlan("cafe", "other", "FREE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("You do not own this location.");
        verify(port, never()).updateTenantPlan(anyString(), anyString());

        service.updateTenantPlan("cafe", "owner", "FREE");
        verify(port).updateTenantPlan("cafe", "FREE");

        // An owner cannot extend their own trial (or anyone else's)
        assertThatThrownBy(() -> service.extendTenantTrial("cafe", "owner", 14))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only a platform administrator may extend a trial.");
        assertThatThrownBy(() -> service.extendTenantTrial("cafe", "other", 14))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only a platform administrator may extend a trial.");
        verify(port, never()).extendTenantTrial(anyString(), anyInt());

        // Blank FASTBITE_PLATFORM_ADMINS means nobody is an admin
        var noAdmins = new es.brasatech.fastbite.application.platform.PlatformAdmins("   ,  ");
        assertThat(noAdmins.isAdmin("")).isFalse();
        assertThat(noAdmins.isAdmin("   ")).isFalse();
        assertThat(noAdmins.isAdmin(null)).isFalse();
        assertThat(noAdmins.isAdmin("brasatech")).isFalse();
        var serviceWithoutAdmins = new SubscriptionService(port, gateway,
                new es.brasatech.fastbite.application.platform.PlatformEnvironment("production"), noAdmins);
        assertThatThrownBy(() -> serviceWithoutAdmins.extendTenantTrial("cafe", "brasatech", 14))
                .isInstanceOf(IllegalArgumentException.class);

        // 1-180 days bound is enforced even for platform admins
        assertThatThrownBy(() -> service.extendTenantTrial("cafe", "brasatech", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.extendTenantTrial("cafe", "brasatech", 181))
                .isInstanceOf(IllegalArgumentException.class);
        verify(port, never()).extendTenantTrial(anyString(), anyInt());

        // A platform admin can extend another owner's location
        service.extendTenantTrial("cafe", "brasatech", 14);
        verify(port).extendTenantTrial("cafe", 14);
    }
}
