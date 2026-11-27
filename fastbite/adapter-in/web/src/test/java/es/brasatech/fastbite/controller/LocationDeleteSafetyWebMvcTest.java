package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.application.office.UserService;
import es.brasatech.fastbite.application.platform.LinkSignature;
import es.brasatech.fastbite.application.platform.LinkedLocationService;
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
import es.brasatech.fastbite.application.tenant.TenantDeletionPort;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.tenant.TenantSignupService;
import es.brasatech.fastbite.config.ProductionOnlyConfig;
import es.brasatech.fastbite.config.PublicOrigin;
import es.brasatech.fastbite.config.TenantContextFilter;
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
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LocationDeleteSafetyWebMvcTest {

    private static final String OWNER = "ana";
    private static final String OTHER = "bob";
    private static final String TENANT = "mycafe";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");

    @WebMvcTest(controllers = {OwnerWorkspaceController.class, TenantMaintenanceController.class},
            properties = "spring.messages.basename=i18n/messages")
    @ContextConfiguration(classes = {
            TestConfig.class, OwnerWorkspaceController.class, TenantMaintenanceController.class,
            EnvironmentAdvice.class, PlatformEnvironment.class, PlatformLink.class,
            PlatformAdmins.class, ThemeCatalog.class, SecurityConfig.class,
            TenantRoutingResolver.class, ProductionOnlyConfig.class, PublicOrigin.class, WebConfig.class
    })
    @TestPropertySource(properties = {
            "fastbite.environment=production",
            "fastbite.link.url=",
            "fastbite.link.standalone=true"
    })
    @Nested
    class OwnerConsoleAndDownload {

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
        void setUp() {
            routingResolver.evictTenant(TENANT);
            TenantLocation active = new TenantLocation("1", OWNER, TENANT, "RESTAURANT", null, null, false, false, null);
            when(tenantLocationService.getLocation(TENANT)).thenReturn(Optional.of(active));
            when(tenantLocationService.isClosed(TENANT)).thenReturn(false);
            when(tenantLocationService.getLocationsByOwner(OWNER)).thenReturn(List.of(active));
            when(tenantLocationService.isOwnerOf(OWNER, TENANT)).thenReturn(true);
            when(tenantLocationService.isOwnerOf(OTHER, TENANT)).thenReturn(false);
            when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
            doThrow(new IllegalArgumentException("You do not own this location."))
                    .when(workspace).requireOwner(eq(OTHER), eq(TENANT));
            when(workspace.cashFloat(OWNER, TENANT)).thenReturn(BigDecimal.ZERO);
            when(workspace.summary(OWNER, TENANT)).thenReturn(new OwnerWorkspaceService.Summary(
                    SetupProgress.EMPTY, 2, RestaurantSettings.DEFAULTS, FiscalProfile.EMPTY, null, false, false, false));
            when(subscriptions.isClosed(TENANT)).thenReturn(false);
            when(subscriptions.account(TENANT, OWNER)).thenReturn(new BillingAccount(
                    TENANT, OWNER, "key", Instant.parse("2026-11-01T00:00:00Z"),
                    "LOCAL_TRIAL", null, null, 0, 0, false, null, null, 0, 0, null, false));
            when(backups.getAvailableTemplates(any())).thenReturn(List.of());
            when(freePlanLock.locked(anyString())).thenReturn(false);
            when(acceptances.mayReceiveOffers(anyString())).thenReturn(false);
            when(locationLifecycle.removalAction(OWNER, TENANT))
                    .thenReturn(LocationLifecycleService.RemovalAction.PRODUCTION_DELETE);
            when(locationLifecycle.undoDays()).thenReturn(7);
        }

        @Test
        void deleteDialogShowsDownloadConfigurationAndEndpointReturnsZip() throws Exception {
            mockMvc.perform(get("/owner/console")
                            .param("view", "location-settings").param("section", "general").param("location", TENANT)
                            .locale(Locale.forLanguageTag("es"))
                            .with(user(ownerPrincipal(OWNER))))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Descargar configuración")))
                    .andExpect(content().string(containsString("/" + TENANT + "/api/backoffice/maintenance/backup")));

            doAnswer(inv -> {
                OutputStream out = inv.getArgument(1);
                try (ZipOutputStream zip = new ZipOutputStream(out)) {
                    zip.putNextEntry(new ZipEntry("backup.json"));
                    zip.write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
                return null;
            }).when(backups).exportBackup(eq(TENANT), any(OutputStream.class));

            byte[] zipBytes = mockMvc.perform(get("/" + TENANT + "/api/backoffice/maintenance/backup")
                            .with(user(ownerPrincipal(OWNER))))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", containsString("application/zip")))
                    .andExpect(header().string("Content-Disposition", containsString(TENANT + "_backup.zip")))
                    .andReturn().getResponse().getContentAsByteArray();

            // ZIP magic header PK\x03\x04
            assertThat(zipBytes.length).isGreaterThan(4);
            assertThat(zipBytes[0]).isEqualTo((byte) 'P');
            assertThat(zipBytes[1]).isEqualTo((byte) 'K');
        }

        @Test
        void pendingDeletionSectionShowsDeadlineAndUndoEndpointRestoresOrRefuses() throws Exception {
            Instant eraseAfter = NOW.plus(7, ChronoUnit.DAYS);
            TenantLocation pending = new TenantLocation("1", OWNER, TENANT, "RESTAURANT", null, null,
                    false, false, null, NOW, eraseAfter);
            when(tenantLocationService.getLocation(TENANT)).thenReturn(Optional.of(pending));
            when(tenantLocationService.getLocationsByOwner(OWNER)).thenReturn(List.of(pending));

            mockMvc.perform(get("/owner/console")
                            .param("view", "locations")
                            .locale(Locale.forLanguageTag("es"))
                            .with(user(ownerPrincipal(OWNER))))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Pendientes de eliminar")))
                    .andExpect(content().string(containsString("Deshacer")))
                    .andExpect(content().string(containsString("/owner/workspace/" + TENANT + "/undo-delete")));

            // Non-owner -> 404
            mockMvc.perform(post("/owner/workspace/" + TENANT + "/undo-delete")
                            .with(user(ownerPrincipal(OTHER)))
                            .with(csrf()))
                    .andExpect(status().isNotFound());

            // Missing CSRF -> 403
            mockMvc.perform(post("/owner/workspace/" + TENANT + "/undo-delete")
                            .with(user(ownerPrincipal(OWNER))))
                    .andExpect(status().isForbidden());

            // Valid undo -> 302 + notice
            when(locationLifecycle.undoDelete(OWNER, TENANT))
                    .thenReturn(new LocationLifecycleService.UndoResult(
                            LocationLifecycleService.UndoResult.Outcome.RESTORED, null));
            mockMvc.perform(post("/owner/workspace/" + TENANT + "/undo-delete")
                            .with(user(ownerPrincipal(OWNER)))
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/owner/console?view=locations"))
                    .andExpect(flash().attributeExists("notice"));

            // Expired undo -> 302 + error
            when(locationLifecycle.undoDelete(OWNER, TENANT))
                    .thenReturn(new LocationLifecycleService.UndoResult(
                            LocationLifecycleService.UndoResult.Outcome.UNDO_WINDOW_EXPIRED, null));
            mockMvc.perform(post("/owner/workspace/" + TENANT + "/undo-delete")
                            .with(user(ownerPrincipal(OWNER)))
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/owner/console?view=locations"))
                    .andExpect(flash().attributeExists("error"));
        }
    }

    @Test
    void linkedSandboxHideAndRestoreEndpointsAndFilterVisibility() throws Exception {
        var locations = mock(TenantLocationService.class);
        var deletion = mock(TenantDeletionPort.class);
        var resolver = new TenantRoutingResolver(locations);
        var env = new PlatformEnvironment("sandbox");
        var link = new PlatformLink(env, "https://prod.example", "https://prod.example", SECRET, false);
        var ctrl = new LinkController(env, link, mock(LinkedLocationService.class), mock(PasswordEncoder.class),
                new ObjectMapper(), "1.0.0", locations, mock(ProductService.class), mock(TenantBackupRestorePort.class),
                deletion, resolver, Clock.fixed(NOW, ZoneOffset.UTC), "Europe/Madrid");

        Instant eraseAfter = NOW.plus(7, ChronoUnit.DAYS);
        TenantLocation activeLoc = new TenantLocation("1", OWNER, TENANT, "RESTAURANT", null);
        TenantLocation hiddenLoc = new TenantLocation("1", OWNER, TENANT, "RESTAURANT", null, null,
                false, false, null, NOW, eraseAfter);

        when(locations.getLocation(TENANT)).thenReturn(Optional.of(activeLoc));
        when(locations.isOwnerOf(OWNER, TENANT)).thenReturn(true);
        when(locations.isClosed(TENANT)).thenReturn(false);
        assertThat(resolver.exists(TENANT)).isTrue();

        // Signed hide call marks pending delete and evicts cache
        byte[] hideBody = ("{\"tenantId\":\"" + TENANT + "\",\"ownerUsername\":\"" + OWNER + "\",\"eraseAfter\":\"" + eraseAfter + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        String hideSig = LinkSignature.header(SECRET, NOW, hideBody);
        assertThat(ctrl.hideLocation(hideBody, hideSig, "1.0.0").getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(locations).markPendingDelete(TENANT, OWNER, NOW, eraseAfter);

        // Once hidden, public menu returns 404 with unknownRestaurant=true
        when(locations.getLocation(TENANT)).thenReturn(Optional.of(hiddenLoc));
        var filter = new TenantContextFilter(resolver);
        var reqHidden = new MockHttpServletRequest("GET", "/" + TENANT + "/menu");
        var resHidden = new MockHttpServletResponse();
        filter.doFilter(reqHidden, resHidden, mock(FilterChain.class));
        assertThat(resHidden.getStatus()).isEqualTo(404);
        assertThat(reqHidden.getAttribute(TenantContextFilter.UNKNOWN_RESTAURANT)).isEqualTo(Boolean.TRUE);

        // Signed restore call clears pending delete and evicts cache
        byte[] restoreBody = ("{\"tenantId\":\"" + TENANT + "\",\"ownerUsername\":\"" + OWNER + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        String restoreSig = LinkSignature.header(SECRET, NOW, restoreBody);
        assertThat(ctrl.restoreLocation(restoreBody, restoreSig, "1.0.0").getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(locations).clearPendingDelete(TENANT);

        // Once restored, exists() is true again
        when(locations.getLocation(TENANT)).thenReturn(Optional.of(activeLoc));
        assertThat(resolver.exists(TENANT)).isTrue();
    }
}
