package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.office.UserService;
import es.brasatech.fastbite.application.platform.PlatformAdmins;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.platform.PromotionService;
import es.brasatech.fastbite.application.settings.GuestPrivacyNoticeService;
import es.brasatech.fastbite.application.tenant.AppearancePort;
import es.brasatech.fastbite.application.tenant.FreePlanLock;
import es.brasatech.fastbite.application.tenant.LegalAcceptancePort;
import es.brasatech.fastbite.application.tenant.LocationLifecycleService;
import es.brasatech.fastbite.application.tenant.OwnerStaffService;
import es.brasatech.fastbite.application.tenant.OwnerWorkspaceService;
import es.brasatech.fastbite.application.tenant.SubscriptionService;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.tenant.TenantSignupService;
import es.brasatech.fastbite.config.ProductionOnlyConfig;
import es.brasatech.fastbite.config.PublicOrigin;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.ThemeCatalog;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
import es.brasatech.fastbite.domain.tenant.BillingAccount;
import es.brasatech.fastbite.domain.tenant.SetupProgress;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.security.TenantUser;
import es.brasatech.fastbite.service.StripeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LocationDeleteControllerTest {

    static final String OWNER = "ana";
    static final String OTHER = "bob";
    static final String TENANT = "mycafe";

    @WebMvcTest(controllers = {OwnerWorkspaceController.class, OwnerConsoleController.class},
            properties = "spring.messages.basename=i18n/messages")
    @ContextConfiguration(classes = {
            TestConfig.class, OwnerWorkspaceController.class, OwnerConsoleController.class,
            EnvironmentAdvice.class, PlatformEnvironment.class, PlatformLink.class,
            PlatformAdmins.class, ThemeCatalog.class, SecurityConfig.class,
            TenantRoutingResolver.class, ProductionOnlyConfig.class, PublicOrigin.class, WebConfig.class
    })
    abstract static class Base {
        @Autowired MockMvc mockMvc;
        @Autowired TenantRoutingResolver routingResolver;

        @MockitoBean TenantLocationService tenantLocationService;
        @MockitoBean TenantSignupService tenantSignupService;
        @MockitoBean StripeService stripeService;
        @MockitoBean OwnerWorkspaceService workspace;
        @MockitoBean OwnerStaffService staff;
        @MockitoBean SubscriptionService subscriptions;
        @MockitoBean TenantBackupRestorePort backups;
        @MockitoBean AppearancePort appearance;
        @MockitoBean PromotionService promotionService;
        @MockitoBean FreePlanLock freePlanLock;
        @MockitoBean LegalAcceptancePort acceptances;
        @MockitoBean GuestPrivacyNoticeService privacyNotices;
        @MockitoBean UserService userService;
        @MockitoBean LocationLifecycleService locationLifecycle;

        TenantUser ownerPrincipal(String name) {
            return new TenantUser(name, "secret", true, List.of(new SimpleGrantedAuthority("ROLE_OWNER")), null);
        }

        @BeforeEach
        void baseSetUp() {
            routingResolver.evictTenant(TENANT);
            TenantLocation loc = new TenantLocation("1", OWNER, TENANT, "RESTAURANT", null, null, false, false, null);
            when(tenantLocationService.getLocation(TENANT)).thenReturn(Optional.of(loc));
            when(tenantLocationService.isClosed(TENANT)).thenReturn(false);
            when(tenantLocationService.getLocationsByOwner(OWNER)).thenReturn(List.of(loc));
            when(tenantLocationService.isOwnerOf(OWNER, TENANT)).thenReturn(true);
            when(tenantLocationService.isOwnerOf(OTHER, TENANT)).thenReturn(false);
            when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());

            doThrow(new IllegalArgumentException("You do not own this location."))
                    .when(workspace).requireOwner(eq(OTHER), eq(TENANT));
            when(workspace.cashFloat(OWNER, TENANT)).thenReturn(BigDecimal.ZERO);
            when(subscriptions.isClosed(TENANT)).thenReturn(false);
            when(subscriptions.account(TENANT, OWNER)).thenReturn(new BillingAccount(
                    TENANT, OWNER, "key", Instant.parse("2026-11-01T00:00:00Z"),
                    "LOCAL_TRIAL", null, null, 0, 0, false, null, null, 0, 0, null, false));
            when(backups.getAvailableTemplates(any())).thenReturn(List.of());
            when(freePlanLock.locked(anyString())).thenReturn(false);
            when(acceptances.mayReceiveOffers(anyString())).thenReturn(false);
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "fastbite.environment=sandbox",
            "fastbite.link.url=https://fastbite.example",
            "fastbite.link.secret=0123456789abcdef0123456789abcdef"
    })
    class LinkedSandbox extends Base {

        @BeforeEach
        void setUpSandbox() {
            when(workspace.summary(OWNER, TENANT)).thenReturn(new OwnerWorkspaceService.Summary(
                    SetupProgress.EMPTY, 3, RestaurantSettings.DEFAULTS, FiscalProfile.EMPTY, null, false, false, true));
            when(locationLifecycle.removalAction(OWNER, TENANT))
                    .thenReturn(LocationLifecycleService.RemovalAction.SANDBOX_DELETE);
        }

        @Test
        void dangerZoneRendersInEsEnPtAndDeletesWithoutForwardingToProduction() throws Exception {
            mockMvc.perform(get("/owner/console")
                            .param("view", "location-settings").param("section", "general").param("location", TENANT)
                            .locale(Locale.forLanguageTag("es"))
                            .with(user(ownerPrincipal(OWNER))))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Zona peligrosa")))
                    .andExpect(content().string(containsString("Eliminar este local de pruebas")))
                    .andExpect(content().string(containsString("id=\"location-removal-dialog\"")))
                    .andExpect(content().string(containsString("/owner/workspace/mycafe/delete")));

            mockMvc.perform(get("/owner/console")
                            .param("view", "location-settings").param("section", "general").param("location", TENANT)
                            .locale(Locale.ENGLISH)
                            .with(user(ownerPrincipal(OWNER))))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Danger zone")))
                    .andExpect(content().string(containsString("Delete this practice location")));

            mockMvc.perform(get("/owner/console")
                            .param("view", "location-settings").param("section", "general").param("location", TENANT)
                            .locale(Locale.forLanguageTag("pt"))
                            .with(user(ownerPrincipal(OWNER))))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Zona de perigo")))
                    .andExpect(content().string(containsString("Eliminar este local de testes")));

            // Legacy /owner/delete-location stays in sandbox and never redirects to production
            mockMvc.perform(post("/owner/delete-location")
                            .param("tenantId", TENANT)
                            .with(user(ownerPrincipal(OWNER)))
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/owner/console?view=location-settings&section=general&location=" + TENANT));

            when(locationLifecycle.deleteLocation(OWNER, TENANT, TENANT))
                    .thenReturn(new LocationLifecycleService.DeleteResult(LocationLifecycleService.DeleteResult.Outcome.DELETED, null));

            mockMvc.perform(post("/owner/workspace/" + TENANT + "/delete")
                            .param("confirmName", TENANT)
                            .with(user(ownerPrincipal(OWNER)))
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/owner/console?view=locations"))
                    .andExpect(flash().attributeExists("notice"));

            verify(locationLifecycle).deleteLocation(OWNER, TENANT, TENANT);
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "fastbite.environment=production",
            "fastbite.link.url=",
            "fastbite.link.standalone=true"
    })
    class Production extends Base {

        @BeforeEach
        void setUpProduction() {
            when(workspace.summary(OWNER, TENANT)).thenReturn(new OwnerWorkspaceService.Summary(
                    SetupProgress.EMPTY, 2, RestaurantSettings.DEFAULTS, FiscalProfile.EMPTY, null, false, false, false));
        }

        @Test
        void neverIssuedTicketShowsDeleteAndDeletesOnMatch() throws Exception {
            when(locationLifecycle.removalAction(OWNER, TENANT))
                    .thenReturn(LocationLifecycleService.RemovalAction.PRODUCTION_DELETE);

            mockMvc.perform(get("/owner/console")
                            .param("view", "location-settings").param("section", "general").param("location", TENANT)
                            .locale(Locale.forLanguageTag("es"))
                            .with(user(ownerPrincipal(OWNER))))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Zona peligrosa")))
                    .andExpect(content().string(containsString("Eliminar este local")))
                    .andExpect(content().string(containsString("/owner/workspace/mycafe/delete")))
                    .andExpect(content().string(not(containsString("/owner/workspace/mycafe/close"))));

            when(locationLifecycle.deleteLocation(OWNER, TENANT, TENANT))
                    .thenReturn(new LocationLifecycleService.DeleteResult(LocationLifecycleService.DeleteResult.Outcome.DELETED, null));

            mockMvc.perform(post("/owner/workspace/" + TENANT + "/delete")
                            .param("confirmName", TENANT)
                            .with(user(ownerPrincipal(OWNER)))
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/owner/console?view=locations"))
                    .andExpect(flash().attributeExists("notice"));
        }

        @Test
        void withTicketsRefusesDeleteAndOffersClose() throws Exception {
            when(locationLifecycle.removalAction(OWNER, TENANT))
                    .thenReturn(LocationLifecycleService.RemovalAction.PRODUCTION_CLOSE);

            mockMvc.perform(get("/owner/console")
                            .param("view", "location-settings").param("section", "general").param("location", TENANT)
                            .locale(Locale.forLanguageTag("es"))
                            .with(user(ownerPrincipal(OWNER))))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Zona peligrosa")))
                    .andExpect(content().string(containsString("Cerrar el local")))
                    .andExpect(content().string(containsString("/owner/workspace/mycafe/close")))
                    .andExpect(content().string(containsString("4 años")));

            when(locationLifecycle.deleteLocation(OWNER, TENANT, TENANT))
                    .thenReturn(new LocationLifecycleService.DeleteResult(LocationLifecycleService.DeleteResult.Outcome.HAS_TICKETS, null));

            mockMvc.perform(post("/owner/workspace/" + TENANT + "/delete")
                            .param("confirmName", TENANT)
                            .with(user(ownerPrincipal(OWNER)))
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/owner/console?view=location-settings&section=general&location=" + TENANT))
                    .andExpect(flash().attributeExists("error"));

            when(locationLifecycle.closeLocation(OWNER, TENANT, TENANT))
                    .thenReturn(new LocationLifecycleService.CloseResult(LocationLifecycleService.CloseResult.Outcome.CLOSED, null));

            mockMvc.perform(post("/owner/workspace/" + TENANT + "/close")
                            .param("confirmName", TENANT)
                            .with(user(ownerPrincipal(OWNER)))
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/owner/console?view=locations"))
                    .andExpect(flash().attributeExists("notice"));
        }

        @Test
        void nonOwnerGets404AndMissingCsrfGets403AndClosedGets409() throws Exception {
            mockMvc.perform(post("/owner/workspace/" + TENANT + "/delete")
                            .param("confirmName", TENANT)
                            .with(user(ownerPrincipal(OTHER)))
                            .with(csrf()))
                    .andExpect(status().isNotFound());

            mockMvc.perform(post("/owner/workspace/" + TENANT + "/close")
                            .param("confirmName", TENANT)
                            .with(user(ownerPrincipal(OTHER)))
                            .with(csrf()))
                    .andExpect(status().isNotFound());

            mockMvc.perform(post("/owner/workspace/" + TENANT + "/delete")
                            .param("confirmName", TENANT)
                            .with(user(ownerPrincipal(OWNER))))
                    .andExpect(status().isForbidden());

            when(tenantLocationService.isClosed(TENANT)).thenReturn(true);
            mockMvc.perform(post("/owner/workspace/" + TENANT + "/delete")
                            .param("confirmName", TENANT)
                            .with(user(ownerPrincipal(OWNER)))
                            .with(csrf()))
                    .andExpect(status().isConflict());
            mockMvc.perform(post("/owner/workspace/" + TENANT + "/close")
                            .param("confirmName", TENANT)
                            .with(user(ownerPrincipal(OWNER)))
                            .with(csrf()))
                    .andExpect(status().isConflict());
        }

        @Test
        void closedLocationsListHidesFromActiveAndShowsUnderCerrados() throws Exception {
            TenantLocation active = new TenantLocation("1", OWNER, "openbistro", "RESTAURANT", null, null, false, false, null);
            TenantLocation closed = new TenantLocation("2", OWNER, "closedbistro", "RESTAURANT", null, null, false, false,
                    Instant.parse("2026-11-24T10:00:00Z"));
            when(tenantLocationService.getLocationsByOwner(OWNER)).thenReturn(List.of(active, closed));
            when(tenantLocationService.isClosed("openbistro")).thenReturn(false);
            when(tenantLocationService.isClosed("closedbistro")).thenReturn(true);
            when(subscriptions.isClosed("openbistro")).thenReturn(false);
            when(subscriptions.isClosed("closedbistro")).thenReturn(true);
            when(workspace.summary(OWNER, "openbistro")).thenReturn(new OwnerWorkspaceService.Summary(
                    SetupProgress.EMPTY, 1, RestaurantSettings.DEFAULTS, FiscalProfile.EMPTY, null, false, false, false));

            mockMvc.perform(get("/owner/console")
                            .param("view", "locations")
                            .locale(Locale.forLanguageTag("es"))
                            .with(user(ownerPrincipal(OWNER))))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Locales cerrados")))
                    .andExpect(content().string(containsString("closedbistro")))
                    .andExpect(content().string(containsString("Cerrado el 24/11/2026")))
                    .andExpect(content().string(containsString("Registros fiscales y tickets")));
        }
    }
}
