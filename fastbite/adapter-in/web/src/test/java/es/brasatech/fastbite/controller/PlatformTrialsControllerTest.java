package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.platform.PlatformAdmins;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.tenant.SubscriptionService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.tenant.BillingAccount;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.security.TenantUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformTrialsControllerTest {

    private static MockHttpSession ownerSession(String username) {
        var authorities = AuthorityUtils.createAuthorityList("ROLE_OWNER");
        var user = new TenantUser(username, "hash", true, authorities, null);
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT",
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities)));
        return session;
    }

    @Nested
    @WebMvcTest(controllers = BillingController.class)
    @ContextConfiguration(classes = {TestConfig.class, BillingController.class, PlatformOfferAdvice.class,
            BadRequestAdvice.class, SecurityConfig.class, TenantRoutingResolver.class, WebConfig.class,
            PlatformEnvironment.class, PlatformAdmins.class})
    @TestPropertySource(properties = {
            "fastbite.environment=production",
            "fastbite.platform.admins=brasatech"
    })
    class InProductionWithConfiguredAdmin {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private SubscriptionService subscriptions;

        @MockitoBean
        private TenantLocationService tenantLocationService;

        @BeforeEach
        void setUp() {
            es.brasatech.fastbite.config.RegisteredTenants.all(tenantLocationService);
            when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        }

        @Test
        void anonymousUserAndRegularOwnerGet404ForPlatformTrialsPageAndExtendEndpoint() throws Exception {
            mockMvc.perform(get("/owner/platform/trials"))
                    .andExpect(status().isNotFound());

            mockMvc.perform(post("/owner/platform/trials/cafe/extend").param("days", "14").with(csrf()))
                    .andExpect(status().isNotFound());

            mockMvc.perform(get("/owner/platform/trials").session(ownerSession("cafeowner")))
                    .andExpect(status().isNotFound());

            mockMvc.perform(post("/owner/platform/trials/cafe/extend").param("days", "14")
                            .session(ownerSession("cafeowner")).with(csrf()))
                    .andExpect(status().isNotFound());

            verify(subscriptions, never()).extendTenantTrial(anyString(), anyString(), anyInt());
        }

        @Test
        void platformAdminViewsTrialsAndExtendsAnotherOwnersLocation() throws Exception {
            var account = new BillingAccount("cafe", "cafeowner", "key", Instant.now(), "LOCAL_TRIAL",
                    null, null, 0, 0, false, null, null, 0, 0);
            when(subscriptions.listAllAccounts("brasatech")).thenReturn(List.of(account));

            mockMvc.perform(get("/owner/platform/trials").session(ownerSession("brasatech")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("cafe")))
                    .andExpect(content().string(containsString("cafeowner")));

            mockMvc.perform(post("/owner/platform/trials/cafe/extend").param("days", "14")
                            .session(ownerSession("brasatech")).with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/owner/platform/trials"));

            verify(subscriptions).extendTenantTrial("cafe", "brasatech", 14);
        }
    }

    @Nested
    @WebMvcTest(controllers = BillingController.class)
    @ContextConfiguration(classes = {TestConfig.class, BillingController.class, PlatformOfferAdvice.class,
            BadRequestAdvice.class, SecurityConfig.class, TenantRoutingResolver.class, WebConfig.class,
            PlatformEnvironment.class, PlatformAdmins.class})
    @TestPropertySource(properties = {
            "fastbite.environment=sandbox",
            "fastbite.platform.admins=brasatech"
    })
    class InSandbox {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private SubscriptionService subscriptions;

        @MockitoBean
        private TenantLocationService tenantLocationService;

        @BeforeEach
        void setUp() {
            es.brasatech.fastbite.config.RegisteredTenants.all(tenantLocationService);
            when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        }

        @Test
        void platformTrialsPageIs404InSandboxEvenForPlatformAdmin() throws Exception {
            mockMvc.perform(get("/owner/platform/trials").session(ownerSession("brasatech")))
                    .andExpect(status().isNotFound());

            mockMvc.perform(post("/owner/platform/trials/cafe/extend").param("days", "14")
                            .session(ownerSession("brasatech")).with(csrf()))
                    .andExpect(status().isNotFound());

            verify(subscriptions, never()).extendTenantTrial(anyString(), anyString(), anyInt());
        }
    }
}
