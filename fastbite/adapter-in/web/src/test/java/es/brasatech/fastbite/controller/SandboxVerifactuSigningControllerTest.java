package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.platform.PlatformAdmins;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.platform.PromotionService;
import es.brasatech.fastbite.application.settings.GuestPrivacyNoticeService;
import es.brasatech.fastbite.application.tenant.AppearancePort;
import es.brasatech.fastbite.application.tenant.FreePlanLock;
import es.brasatech.fastbite.application.tenant.LegalAcceptancePort;
import es.brasatech.fastbite.application.tenant.OwnerStaffService;
import es.brasatech.fastbite.application.tenant.OwnerWorkspacePort;
import es.brasatech.fastbite.application.tenant.OwnerWorkspaceService;
import es.brasatech.fastbite.application.tenant.SubscriptionService;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationService;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationService.StartSigningResult;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.ThemeCatalog;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
import es.brasatech.fastbite.domain.tenant.SetupProgress;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorization;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorizationStatus;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.security.TenantUser;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

@WebMvcTest(controllers = OwnerWorkspaceController.class, properties = "spring.messages.basename=i18n/messages")
@ContextConfiguration(classes = {
        TestConfig.class, OwnerWorkspaceController.class,
        EnvironmentAdvice.class, PlatformEnvironment.class, PlatformLink.class,
        PlatformAdmins.class, ThemeCatalog.class, SecurityConfig.class,
        TenantRoutingResolver.class, WebConfig.class
})
@TestPropertySource(properties = {
        "fastbite.environment=sandbox",
        "fastbite.link.url=https://fastbite.example",
        "fastbite.link.secret=0123456789abcdef0123456789abcdef"
})
class SandboxVerifactuSigningControllerTest {

    private static final String OWNER = "ana";
    private static final String TENANT = "bistro";
    private static final FiscalProfile COMPLETE = new FiscalProfile(
            "Bistro Ana SL", "Bistro Ana", "B09767898",
            "Calle Mayor 1", "28013", "Madrid", "Madrid",
            "600123456", "ana@bistro.es", "BA", new BigDecimal("10"));

    @Autowired MockMvc mockMvc;
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
    @MockitoBean VerifactuAuthorizationService verifactuAuthorizations;

    private TenantUser ownerPrincipal() {
        return new TenantUser(OWNER, "secret", true, List.of(new SimpleGrantedAuthority("ROLE_OWNER")), null);
    }

    @BeforeEach
    void setUp() {
        routingResolver.evictTenant(TENANT);
        TenantLocation loc = new TenantLocation("1", OWNER, TENANT, "RESTAURANT", null, null, false, false, null);
        when(tenantLocationService.getLocation(TENANT)).thenReturn(Optional.of(loc));
        when(tenantLocationService.isClosed(TENANT)).thenReturn(false);
        when(tenantLocationService.getLocationsByOwner(OWNER)).thenReturn(List.of(loc));
        when(tenantLocationService.isOwnerOf(OWNER, TENANT)).thenReturn(true);
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());

        when(subscriptions.isClosed(TENANT)).thenReturn(false);
        when(subscriptions.account(TENANT, OWNER)).thenReturn(null);
        when(staff.listStaff(anyString(), anyString())).thenReturn(List.of());
        when(backups.getAvailableTemplates(any())).thenReturn(List.of());
        when(freePlanLock.locked(anyString())).thenReturn(false);
        when(acceptances.mayReceiveOffers(anyString())).thenReturn(false);
        when(promotionService.getUndoablePromotion(anyString(), anyString())).thenReturn(Optional.empty());
        when(workspace.fiscalProfile(OWNER, TENANT)).thenReturn(COMPLETE);
        when(workspace.ownerSigner(OWNER)).thenReturn(Optional.of(new OwnerWorkspacePort.OwnerSigner("Ana García", "ana.verified@bistro.es")));
        when(workspace.summary(OWNER, TENANT)).thenReturn(new OwnerWorkspaceService.Summary(
                new SetupProgress(true, false, false, false), 5, RestaurantSettings.DEFAULTS,
                COMPLETE, null, false, false, true, false, false));
    }

    @Test
    void sandboxOverviewShowsVerifactuStepWithNoLegalValueWordingInAllLocales() throws Exception {
        when(verifactuAuthorizations.isSigningConfigured()).thenReturn(true);
        when(verifactuAuthorizations.findCurrentForNif("B09767898")).thenReturn(Optional.empty());

        String esHtml = mockMvc.perform(get("/owner/console?view=today&location=" + TENANT)
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(esHtml)
                .contains("id=\"opening-step-verifactu\"")
                .contains("Firma de prueba sin validez legal")
                .contains("Firmar con certificado digital");

        String enHtml = mockMvc.perform(get("/owner/console?view=today&location=" + TENANT)
                        .locale(Locale.ENGLISH)
                        .with(user(ownerPrincipal())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(enHtml)
                .contains("Test signature with no legal value")
                .contains("Sign with digital certificate");

        String ptHtml = mockMvc.perform(get("/owner/console?view=today&location=" + TENANT)
                        .locale(Locale.forLanguageTag("pt"))
                        .with(user(ownerPrincipal())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ptHtml)
                .contains("Assinatura de teste sem valor legal")
                .contains("Assinar com certificado digital");
    }

    @Test
    void linkedSandboxRunsAuthorizeAndRefreshInSandboxNotForwardedToProduction() throws Exception {
        VerifactuAuthorization auth = new VerifactuAuthorization(
                UUID.randomUUID(), "B09767898", "Bistro Ana SL",
                VerifactuAuthorizationStatus.IN_PROGRESS, "req-sbx-1", "verifactu-B09767898-1", 1,
                "tpl-1", "Ana García", null, null, null, null, null, null, OWNER,
                Instant.now(), Instant.now(), null, null, Instant.now(), 0, 0, null);
        when(verifactuAuthorizations.startSigning(eq(COMPLETE), eq(OWNER), eq("Ana García"), eq("ana.verified@bistro.es"), eq("es")))
                .thenReturn(StartSigningResult.signingUrl("https://digital-sign.example/sign/sandbox-token", auth));

        mockMvc.perform(post("/owner/workspace/" + TENANT + "/verifactu/authorize")
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "https://digital-sign.example/sign/sandbox-token"));

        mockMvc.perform(post("/owner/workspace/" + TENANT + "/verifactu/refresh")
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/owner/console?location=" + TENANT));

        verify(verifactuAuthorizations).refreshForNif("B09767898");
    }

    @Test
    void sandboxRefusedLiveEnvironmentShowsClearReasonAndAllowsRetry() throws Exception {
        VerifactuAuthorization liveRefused = new VerifactuAuthorization(
                UUID.randomUUID(), "B09767898", "Bistro Ana SL",
                VerifactuAuthorizationStatus.LIVE_ENVIRONMENT, "req-live-1", "verifactu-B09767898-1", 1,
                "tpl-1", "Ana García", null, null, null, null, null, "LIVE_ENVIRONMENT", OWNER,
                Instant.now(), Instant.now(), null, null, Instant.now(), 1, 0, null);
        when(verifactuAuthorizations.isSigningConfigured()).thenReturn(true);
        when(verifactuAuthorizations.findCurrentForNif("B09767898")).thenReturn(Optional.of(liveRefused));
        when(verifactuAuthorizations.startSigning(eq(COMPLETE), eq(OWNER), eq("Ana García"), eq("ana.verified@bistro.es"), eq("es")))
                .thenReturn(StartSigningResult.unavailable("LIVE_ENVIRONMENT"));

        mockMvc.perform(post("/owner/workspace/" + TENANT + "/verifactu/authorize")
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/owner/console?location=" + TENANT))
                .andExpect(flash().attribute("error", containsString("Firma real rechazada en el entorno de pruebas")));

        mockMvc.perform(get("/owner/console").param("location", TENANT)
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Firma real rechazada en el entorno de pruebas")))
                .andExpect(content().string(containsString("Firmar de nuevo")));
    }

    @Test
    void sandboxValidAuthorizationShowsSignedAndVerifiedEvenWhenCollaborationSwitchIsOff() throws Exception {
        Instant now = Instant.now();
        VerifactuAuthorization valid = new VerifactuAuthorization(
                UUID.randomUUID(), "B09767898", "Bistro Ana SL",
                VerifactuAuthorizationStatus.VALID, "req-sbx-ok", "verifactu-B09767898-1", 1,
                "tpl-1", "Ana García", "B09767898", "B09767898/signed.pdf", "sha1",
                "B09767898/evidence.pdf", "sha2", null, OWNER,
                now, now, now, null, now, 1, 0, null);
        when(verifactuAuthorizations.isSigningConfigured()).thenReturn(true);
        when(verifactuAuthorizations.isCollaborationActive()).thenReturn(false);
        when(verifactuAuthorizations.findCurrentForNif("B09767898")).thenReturn(Optional.of(valid));

        mockMvc.perform(get("/owner/console").param("location", TENANT)
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Firmada y verificada")));
    }

    @Test
    void linkedSandboxFallsBackToFiscalOrPracticeEmailWhenVerifiedEmailIsAbsent() throws Exception {
        when(workspace.ownerSigner(OWNER)).thenReturn(Optional.of(new OwnerWorkspacePort.OwnerSigner("Ana García", null)));
        VerifactuAuthorization auth = new VerifactuAuthorization(
                UUID.randomUUID(), "B09767898", "Bistro Ana SL",
                VerifactuAuthorizationStatus.IN_PROGRESS, "req-sbx-fb", "verifactu-B09767898-1", 1,
                "tpl-1", "Ana García", null, null, null, null, null, null, OWNER,
                Instant.now(), Instant.now(), null, null, Instant.now(), 0, 0, null);
        when(verifactuAuthorizations.startSigning(eq(COMPLETE), eq(OWNER), eq("Ana García"), eq("ana@bistro.es"), eq("es")))
                .thenReturn(StartSigningResult.signingUrl("https://digital-sign.example/sign/sandbox-fb", auth));

        mockMvc.perform(post("/owner/workspace/" + TENANT + "/verifactu/authorize")
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "https://digital-sign.example/sign/sandbox-fb"));

        FiscalProfile noEmail = new FiscalProfile(
                "Bistro Ana SL", "Bistro Ana", "B09767898",
                "Calle Mayor 1", "28013", "Madrid", "Madrid",
                "600123456", null, "BA", new BigDecimal("10"));
        when(workspace.fiscalProfile(OWNER, TENANT)).thenReturn(noEmail);
        when(verifactuAuthorizations.startSigning(eq(noEmail), eq(OWNER), eq("Ana García"), eq("sandbox@brasatech.es"), eq("es")))
                .thenReturn(StartSigningResult.signingUrl("https://digital-sign.example/sign/sandbox-default", auth));

        mockMvc.perform(post("/owner/workspace/" + TENANT + "/verifactu/authorize")
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "https://digital-sign.example/sign/sandbox-default"));
    }
}
