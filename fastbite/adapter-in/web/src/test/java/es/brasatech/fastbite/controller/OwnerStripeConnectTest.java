package es.brasatech.fastbite.controller;

import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.service.StripeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OwnerStripeConnectTest {

    private final TenantLocationService tenantLocationService = mock(TenantLocationService.class);
    private final StripeService stripeService = mock(StripeService.class);
    private final es.brasatech.fastbite.application.tenant.TenantSignupService tenantSignupService = mock(es.brasatech.fastbite.application.tenant.TenantSignupService.class);
    private final es.brasatech.fastbite.config.TenantRoutingResolver tenantResolver = mock(es.brasatech.fastbite.config.TenantRoutingResolver.class);

    private MockMvc mockMvc;
    private final Principal principal = () -> "testowner";

    @BeforeEach
    void setUp() {
        OwnerConsoleController controller = new OwnerConsoleController(
                tenantLocationService, tenantSignupService, tenantResolver, stripeService
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void connectStripeRedirectsToStripeOnboarding() throws Exception {
        when(tenantLocationService.isOwnerOf("testowner", "cafe")).thenReturn(true);
        when(stripeService.isConfigured()).thenReturn(true);
        when(tenantLocationService.getLocation("cafe")).thenReturn(Optional.of(
                new TenantLocation("id1", "testowner", "cafe", "RESTAURANT", null, "acct_existing")
        ));

        AccountLink link = new AccountLink();
        link.setUrl("https://connect.stripe.com/setup/s/12345");
        when(stripeService.createAccountLink(eq("acct_existing"), any(), any())).thenReturn(link);

        mockMvc.perform(post("/owner/connect-stripe")
                        .param("tenantId", "cafe")
                        .principal(principal))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("https://connect.stripe.com/setup/s/12345"));

        verify(stripeService, never()).createConnectedAccount(any(), any(), any());
    }

    @Test
    void connectStripeCreatesAccountIfNotExists() throws Exception {
        when(tenantLocationService.isOwnerOf("testowner", "cafe")).thenReturn(true);
        when(stripeService.isConfigured()).thenReturn(true);
        when(tenantLocationService.getLocation("cafe")).thenReturn(Optional.of(
                new TenantLocation("id1", "testowner", "cafe", "RESTAURANT", null, null)
        ));

        Account account = new Account();
        account.setId("acct_new123");
        when(stripeService.createConnectedAccount(eq("cafe"), any(), eq("cafe"))).thenReturn(account);

        AccountLink link = new AccountLink();
        link.setUrl("https://connect.stripe.com/setup/s/67890");
        when(stripeService.createAccountLink(eq("acct_new123"), any(), any())).thenReturn(link);

        mockMvc.perform(post("/owner/connect-stripe")
                        .param("tenantId", "cafe")
                        .principal(principal))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("https://connect.stripe.com/setup/s/67890"));

        verify(tenantLocationService).updateStripeAccountId("testowner", "cafe", "acct_new123");
    }

    @Test
    void connectStripeRejectsNonOwner() throws Exception {
        when(tenantLocationService.isOwnerOf("testowner", "other")).thenReturn(false);

        mockMvc.perform(post("/owner/connect-stripe")
                        .param("tenantId", "other")
                        .principal(principal))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/owner/console?view=locations"))
                .andExpect(flash().attribute("error", "You do not own this location."));
    }

    @Test
    void connectStripeReturnRedirectsBackToPaymentsSection() throws Exception {
        mockMvc.perform(get("/owner/connect-stripe/return")
                        .param("tenantId", "cafe")
                        .principal(principal))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/owner/console?view=location-settings&section=payments&location=cafe"))
                .andExpect(flash().attributeExists("success"));
    }
}
