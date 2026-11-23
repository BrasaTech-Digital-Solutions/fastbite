package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.platform.PlatformAdmins;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.tenant.SubscriptionService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.tenant.OwnerWorkspaceService;
import es.brasatech.fastbite.application.tenant.AppearancePort;
import es.brasatech.fastbite.config.ThemeCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OwnerBillingNotFoundTest {
    @Test void locationAppearanceRejectsInaccessibleLocations() throws Exception {
        var workspace = mock(OwnerWorkspaceService.class);
        doThrow(new IllegalArgumentException("You do not own this location.")).when(workspace).requireOwner(anyString(), anyString());
        var mvc = MockMvcBuilders.standaloneSetup(new AppearanceController(mock(AppearancePort.class), mock(ThemeCatalog.class), workspace)).build();
        var auth = UsernamePasswordAuthenticationToken.authenticated("owner", null, java.util.List.of());
        for (String tenant : new String[]{"absent", "malformed-tenant", "foreign"})
            mvc.perform(post("/owner/workspace/" + tenant + "/appearance").param("theme", "inherit").principal(auth))
                    .andExpect(status().isNotFound());
    }
    @Test void inaccessibleLocationBillingEndpoints() throws Exception {
        var subscriptions = mock(SubscriptionService.class);
        var locations = mock(TenantLocationService.class);
        when(subscriptions.account(anyString(), anyString())).thenThrow(new IllegalArgumentException("You do not own this location."));
        when(subscriptions.checkout(anyString(), anyString())).thenThrow(new IllegalArgumentException("You do not own this location."));
        when(subscriptions.portal(anyString(), anyString())).thenThrow(new IllegalArgumentException("You do not own this location."));
        doThrow(new IllegalArgumentException("You do not own this location.")).when(subscriptions).refresh(anyString(), anyString());
        doThrow(new IllegalArgumentException("You do not own this location.")).when(subscriptions).updateTenantPlan(anyString(), anyString(), anyString());
        var messages = new StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        var controller = new BillingController(subscriptions, locations, mock(PlatformEnvironment.class), mock(PlatformAdmins.class));
        ReflectionTestUtils.setField(controller, "messages", messages);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ResourceNotFoundAdvice()).build();
        var auth = UsernamePasswordAuthenticationToken.authenticated("owner", null, java.util.List.of());
        for (String tenant : new String[]{"absent", "malformed-tenant", "foreign"}) {
            mvc.perform(get("/owner/billing/" + tenant).principal(auth)).andExpect(status().isNotFound());
            for (String action : new String[]{"checkout", "portal", "refresh"})
                mvc.perform(post("/owner/billing/" + tenant + "/" + action).principal(auth)).andExpect(status().isNotFound());
            mvc.perform(post("/owner/billing/" + tenant + "/override-plan").param("plan", "RESTAURANT").principal(auth))
                    .andExpect(status().isNotFound());
        }
    }
}
