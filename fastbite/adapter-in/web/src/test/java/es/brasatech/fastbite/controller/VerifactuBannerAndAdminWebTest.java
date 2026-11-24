package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.platform.PlatformAdmins;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.platform.PromotionService;
import es.brasatech.fastbite.application.settings.GuestPrivacyNoticeService;
import es.brasatech.fastbite.application.tenant.AppearancePort;
import es.brasatech.fastbite.application.tenant.FreePlanLock;
import es.brasatech.fastbite.application.tenant.LegalAcceptancePort;
import es.brasatech.fastbite.application.tenant.OwnerStaffService;
import es.brasatech.fastbite.application.tenant.OwnerWorkspaceService;
import es.brasatech.fastbite.application.tenant.SubscriptionService;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationGate;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationGate.HoldReason;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationService;
import es.brasatech.fastbite.config.PriceDialect;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.ThemeCatalog;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.OrderStatus;
import es.brasatech.fastbite.domain.order.ServiceType;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
import es.brasatech.fastbite.domain.tenant.SetupProgress;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorization;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorizationStatus;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.security.TenantUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VerifactuBannerAndAdminWebTest {

    static final String ADMIN = "ricardo";
    static final String OWNER = "ana";
    static final String TENANT = "lacasita";

    @WebMvcTest(controllers = {OwnerWorkspaceController.class, PlatformVerifactuAdminController.class},
            properties = "spring.messages.basename=i18n/messages")
    @ContextConfiguration(classes = {
            TestConfig.class, OwnerWorkspaceController.class, PlatformVerifactuAdminController.class,
            EnvironmentAdvice.class, PlatformEnvironment.class, PlatformLink.class,
            PlatformAdmins.class, PriceDialect.class, ThemeCatalog.class, SecurityConfig.class,
            TenantRoutingResolver.class, WebConfig.class
    })
    abstract static class BaseWebTest {
        @Autowired MockMvc mockMvc;
        @Autowired EnvironmentAdvice environmentAdvice;
        @Autowired TenantRoutingResolver routingResolver;

        @MockitoBean TenantLocationService tenantLocationService;
        @MockitoBean OwnerWorkspaceService workspace;
        @MockitoBean OwnerStaffService staff;
        @MockitoBean SubscriptionService subscriptions;
        @MockitoBean TenantBackupRestorePort backups;
        @MockitoBean AppearancePort appearance;
        @MockitoBean PromotionService promotionService;
        @MockitoBean FreePlanLock freePlanLock;
        @MockitoBean LegalAcceptancePort acceptances;
        @MockitoBean GuestPrivacyNoticeService privacyNotices;
        @MockitoBean VerifactuAuthorizationGate gate;
        @MockitoBean VerifactuAuthorizationService authorizations;
        @MockitoBean FiscalProfileService fiscalProfiles;
        @MockitoBean OrderService orders;

        TenantUser ownerPrincipal(String username) {
            return new TenantUser(username, "secret", true, List.of(new SimpleGrantedAuthority("ROLE_OWNER")), null);
        }

        @BeforeEach
        void setUpBase() {
            routingResolver.evictTenant(TENANT);
            TenantLocation loc = new TenantLocation("1", OWNER, TENANT, "RESTAURANT", null, null, false, false, null);
            when(tenantLocationService.getLocation(TENANT)).thenReturn(Optional.of(loc));
            when(tenantLocationService.isClosed(TENANT)).thenReturn(false);
            when(tenantLocationService.getLocationsByOwner(OWNER)).thenReturn(List.of(loc));
            when(tenantLocationService.getLocationsByOwner(ADMIN)).thenReturn(List.of());
            when(tenantLocationService.getAllLocations()).thenReturn(List.of(loc));
            when(tenantLocationService.isOwnerOf(OWNER, TENANT)).thenReturn(true);
            when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());

            when(subscriptions.isClosed(TENANT)).thenReturn(false);
            when(subscriptions.account(anyString(), anyString())).thenReturn(null);
            when(staff.listStaff(anyString(), anyString())).thenReturn(List.of());
            when(backups.getAvailableTemplates(any())).thenReturn(List.of());
            when(freePlanLock.locked(anyString())).thenReturn(false);
            when(acceptances.mayReceiveOffers(anyString())).thenReturn(false);
            when(promotionService.getUndoablePromotion(anyString(), anyString())).thenReturn(Optional.empty());
            when(workspace.ordersNeedingRefund(anyString(), anyString())).thenReturn(List.of());
            when(orders.findOrdersNeedingRefund()).thenReturn(List.of());
            FiscalProfile profile = new FiscalProfile(
                    "La Casita S.L.", "La Casita", "B12345674", "Calle Mayor 1", "28013", "Madrid", "Madrid",
                    null, null, "LC", new BigDecimal("10"));
            when(fiscalProfiles.get()).thenReturn(profile);
            when(authorizations.isCollaborationActive()).thenReturn(true);
            when(authorizations.findCurrentForNif(anyString())).thenReturn(Optional.empty());
            when(gate.holdReason(TENANT)).thenReturn(Optional.of(HoldReason.NOT_SIGNED));

            OwnerWorkspaceService.Summary summary = new OwnerWorkspaceService.Summary(
                    SetupProgress.EMPTY, 2, RestaurantSettings.DEFAULTS,
                    profile,
                    null, false, false, false);
            when(workspace.summary(OWNER, TENANT)).thenReturn(summary);
        }

        @AfterEach
        void clearTenant() {
            TenantContext.clear();
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "fastbite.environment=production",
            "fastbite.platform.admins=ricardo",
            "fastbite.link.url=",
            "fastbite.link.standalone=true"
    })
    class ProductionTests extends BaseWebTest {

        @Test
        void allHoldReasonsRenderTheirOwnerBanner() throws Exception {
            for (Locale locale : List.of(Locale.ENGLISH, Locale.forLanguageTag("es"), Locale.forLanguageTag("pt"))) {
                var messages = java.util.ResourceBundle.getBundle("i18n.messages", locale);
                for (var reason : HoldReason.values()) {
                    when(gate.holdReason(TENANT)).thenReturn(Optional.of(reason));
                    String html = mockMvc.perform(get("/owner/console?view=today&location=" + TENANT)
                                    .locale(locale).with(user(ownerPrincipal(OWNER))))
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                    int start = html.indexOf("class=\"fb-verifactu-banner fb-verifactu-banner--owner\"");
                    assertThat(start).isGreaterThanOrEqualTo(0);
                    String banner = org.unbescape.html.HtmlEscape.unescapeHtml(html.substring(start, html.indexOf("</div>", start)));
                    assertThat(banner).contains(messages.getString("verifactu.banner.owner"),
                            messages.getString(EnvironmentAdvice.holdReasonMessageKey(reason)));
                    switch (reason) {
                        case FISCAL_INCOMPLETE -> assertThat(banner)
                                .contains(messages.getString("verifactu.banner.owner.complete_business"),
                                        "/owner/console?view=location-settings&section=legal&location=" + TENANT)
                                .doesNotContain("#opening-step-verifactu");
                        case COLLABORATION_INACTIVE -> assertThat(banner)
                                .contains(messages.getString("verifactu.banner.owner.collaboration_wait"))
                                .doesNotContain("<a ", "#opening-step-verifactu");
                        default -> assertThat(banner).contains(messages.getString("verifactu.banner.owner.action"), "#opening-step-verifactu");
                    }
                }
            }
        }

        @Test
        void ownerWorkspaceRendersHoldBannerAndNeedsRefundOrders() throws Exception {
            Order refundOrder = new Order(
                    List.of(), 42, "ord-1", LocalDateTime.of(2026, 11, 21, 10, 15), LocalDateTime.of(2026, 11, 21, 10, 16),
                    OrderStatus.CANCELLED, new BigDecimal("18.50"), Order.VERIFACTU_HOLD_NEEDS_REFUND_REASON,
                    OrderPaymentStatus.NEEDS_REFUND, OrderChannel.ONLINE, "es", "guest", "Carlos",
                    ServiceType.TAKEAWAY);
            when(workspace.ordersNeedingRefund(OWNER, TENANT)).thenReturn(List.of(refundOrder));

            String html = mockMvc.perform(get("/owner/console?view=today&location=" + TENANT)
                            .locale(Locale.forLanguageTag("es"))
                            .with(user(ownerPrincipal(OWNER))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(html)
                    .contains("fb-verifactu-banner--owner")
                    .contains("Este local está en pausa y no puede emitir tickets")
                    .contains("#opening-step-verifactu")
                    .contains("Pedidos online pendientes de reembolso")
                    .contains("#42")
                    .contains("Carlos")
                    .contains("18,50\u00A0€");
        }

        @Test
        void bannersSkipApiFragmentAndJsonRequestsAndUseOneLocationLookup() {
            TenantContext.setCurrentTenant(TENANT);

            MockHttpServletRequest apiReq = new MockHttpServletRequest("GET", "/" + TENANT + "/counter/api/catalog/products");
            MockHttpServletRequest fragReq = new MockHttpServletRequest("GET", "/counter/fragments/products");
            MockHttpServletRequest jsonReq = new MockHttpServletRequest("GET", "/" + TENANT + "/menu");
            jsonReq.addHeader("Accept", "application/json");

            assertThat(environmentAdvice.verifactuBanner(apiReq, () -> OWNER)).isNull();
            assertThat(environmentAdvice.verifactuBanner(fragReq, () -> OWNER)).isNull();
            assertThat(environmentAdvice.verifactuBanner(jsonReq, null)).isNull();
            verifyNoInteractions(gate);

            MockHttpServletRequest counterReq = new MockHttpServletRequest("GET", "/" + TENANT + "/counter");
            assertThat(environmentAdvice.verifactuBanner(counterReq, () -> OWNER)).isNotNull();
            verify(tenantLocationService, times(1)).getLocation(TENANT);
            verify(tenantLocationService, times(0)).isClosed(TENANT);
        }

        @Test
        void bannersResolveAudienceForStaffGuestAndOpenLocation() {
            TenantContext.setCurrentTenant(TENANT);

            MockHttpServletRequest counterReq = new MockHttpServletRequest("GET", "/" + TENANT + "/counter");
            var staffBanner = environmentAdvice.verifactuBanner(counterReq, () -> OWNER);
            assertThat(staffBanner).isNotNull();
            assertThat(staffBanner.audience()).isEqualTo("staff");
            assertThat(staffBanner.messageKey()).isEqualTo("verifactu.banner.staff");
            assertThat(staffBanner.reasonKey()).isNull();

            MockHttpServletRequest menuReq = new MockHttpServletRequest("GET", "/" + TENANT + "/menu");
            var guestBanner = environmentAdvice.verifactuBanner(menuReq, null);
            assertThat(guestBanner).isNotNull();
            assertThat(guestBanner.audience()).isEqualTo("guest");
            assertThat(guestBanner.messageKey()).isEqualTo("verifactu.banner.guest");
            assertThat(guestBanner.reasonKey()).isNull();

            when(gate.holdReason(TENANT)).thenReturn(Optional.empty());
            assertThat(environmentAdvice.verifactuBanner(counterReq, () -> OWNER)).isNull();
            assertThat(environmentAdvice.verifactuBanner(menuReq, null)).isNull();
        }

        @Test
        void platformAdminPageReturns404ForAnonymousAndNonAdmin() throws Exception {
            mockMvc.perform(get("/owner/platform/verifactu-holds"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/owner/platform/verifactu-holds")
                            .with(user(ownerPrincipal(OWNER))))
                    .andExpect(status().isNotFound());
        }

        @Test
        void platformAdminPageListsHeldLocationsAndNeedsRefundOrders() throws Exception {
            Order refundOrder = new Order(
                    List.of(), 77, "ord-9", LocalDateTime.of(2026, 11, 21, 11, 0), LocalDateTime.of(2026, 11, 21, 11, 1),
                    OrderStatus.CANCELLED, new BigDecimal("24.00"), Order.VERIFACTU_HOLD_NEEDS_REFUND_REASON,
                    OrderPaymentStatus.NEEDS_REFUND, OrderChannel.ONLINE, "es", "guest", "Lucia",
                    ServiceType.TAKEAWAY);
            when(orders.findOrdersNeedingRefund()).thenReturn(List.of(refundOrder));

            String html = mockMvc.perform(get("/owner/platform/verifactu-holds")
                            .locale(Locale.forLanguageTag("es"))
                            .with(user(ownerPrincipal(ADMIN))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(html)
                    .contains("Bloqueos VERI*FACTU y reembolsos pendientes")
                    .contains("lacasita")
                    .contains("B12345674")
                    .contains("#77")
                    .contains("Lucia")
                    .contains("24,00\u00A0€");
        }

        @Test
        void platformAdminPageListsRevokedLocationsWithRevokedHoldReason() throws Exception {
            TenantLocation loc1 = new TenantLocation("1", OWNER, TENANT, "RESTAURANT", null, null, false, false, null);
            TenantLocation loc2 = new TenantLocation("2", OWNER, "lacasita2", "RESTAURANT", null, null, false, false, null);
            when(tenantLocationService.getAllLocations()).thenReturn(List.of(loc1, loc2));
            when(gate.holdReason(TENANT)).thenReturn(Optional.of(HoldReason.REVOKED));
            when(gate.holdReason("lacasita2")).thenReturn(Optional.of(HoldReason.REVOKED));
            Instant now = Instant.now();
            VerifactuAuthorization revoked = new VerifactuAuthorization(
                    UUID.randomUUID(), "B12345674", "La Casita S.L.",
                    VerifactuAuthorizationStatus.REVOKED, "req-rev", "verifactu-B12345674-1", 1,
                    "tpl-1", "Ana", "B12345674", "p1", "sha1", "p2", "sha2", null, OWNER,
                    now.minusSeconds(60), now, now.minusSeconds(60), now, OWNER, now, 2, 0, null);
            when(authorizations.findCurrentForNif("B12345674")).thenReturn(Optional.of(revoked));

            String html = mockMvc.perform(get("/owner/platform/verifactu-holds")
                            .locale(Locale.forLanguageTag("es"))
                            .with(user(ownerPrincipal(ADMIN))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(html)
                    .contains("lacasita")
                    .contains("lacasita2")
                    .contains("B12345674")
                    .contains("REVOKED");
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "fastbite.environment=sandbox",
            "fastbite.platform.admins=ricardo",
            "fastbite.link.url="
    })
    class SandboxTests extends BaseWebTest {

        @Test
        void sandboxNeverShowsVerifactuBannerAndAdminPageReturns404() throws Exception {
            TenantContext.setCurrentTenant(TENANT);
            MockHttpServletRequest counterReq = new MockHttpServletRequest("GET", "/" + TENANT + "/counter");
            assertThat(environmentAdvice.verifactuBanner(counterReq, () -> OWNER)).isNull();

            mockMvc.perform(get("/owner/platform/verifactu-holds")
                            .with(user(ownerPrincipal(ADMIN))))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "fastbite.environment=production",
            "fastbite.platform.admins=ricardo",
            "fastbite.link.url=https://sandbox.example",
            "fastbite.link.secret=0123456789abcdef0123456789abcdef"
    })
    class LinkedProductionTests extends BaseWebTest {

        @Test
        void holdBannerLinkLandsOnVerifactuStepEvenWhenLocationNeedsStart() throws Exception {
            FiscalProfile profile = new FiscalProfile(
                    "La Casita S.L.", "La Casita", "B12345674", "Calle Mayor 1", "28013", "Madrid", "Madrid",
                    null, null, "LC", new BigDecimal("10"));
            OwnerWorkspaceService.Summary needsStartSummary = new OwnerWorkspaceService.Summary(
                    SetupProgress.EMPTY, 0, RestaurantSettings.DEFAULTS,
                    profile, null, false, false, false);
            when(workspace.summary(OWNER, TENANT)).thenReturn(needsStartSummary);
            when(gate.holdReason(TENANT)).thenReturn(Optional.of(HoldReason.NOT_SIGNED));

            // Initial screen shows the sandbox card and the yellow on-hold banner with ?opening=1#opening-step-verifactu
            String initialHtml = mockMvc.perform(get("/owner/console?view=today&location=" + TENANT)
                            .locale(Locale.forLanguageTag("es"))
                            .with(user(ownerPrincipal(OWNER))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(initialHtml)
                    .contains("production-sandbox-card")
                    .contains("/owner/console?view=today&amp;location=" + TENANT + "&amp;opening=1#opening-step-verifactu")
                    .doesNotContain("id=\"opening-step-verifactu\"");

            // Following the banner link renders the opening checklist containing id="opening-step-verifactu"
            String targetHtml = mockMvc.perform(get("/owner/console?view=today&location=" + TENANT + "&opening=1")
                            .locale(Locale.forLanguageTag("es"))
                            .with(user(ownerPrincipal(OWNER))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(targetHtml)
                    .contains("id=\"opening-step-verifactu\"")
                    .contains("B12345674")
                    .doesNotContain("production-sandbox-card");
        }
    }
}
