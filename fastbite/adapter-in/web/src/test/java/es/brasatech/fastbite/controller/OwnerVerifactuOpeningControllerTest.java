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
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationService.RevokeResult;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationService.StartSigningResult;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.ThemeCatalog;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
import es.brasatech.fastbite.domain.tenant.BillingAccount;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
        "fastbite.environment=production",
        "fastbite.link.url=",
        "fastbite.link.standalone=true"
})
class OwnerVerifactuOpeningControllerTest {

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
        when(subscriptions.account(TENANT, OWNER)).thenReturn(
                new BillingAccount(TENANT, OWNER, "key-1", null, "LOCAL_TRIAL",
                        null, null, 0, 0, false, null, null, 0, 0));
        when(subscriptions.resolveOwnerEmail(TENANT, OWNER)).thenReturn(Optional.of("ana@bistro.es"));
        when(staff.listStaff(anyString(), anyString())).thenReturn(List.of());
        when(backups.getAvailableTemplates(any())).thenReturn(List.of());
        when(freePlanLock.locked(anyString())).thenReturn(false);
        when(acceptances.mayReceiveOffers(anyString())).thenReturn(false);
        when(promotionService.getUndoablePromotion(anyString(), anyString())).thenReturn(Optional.empty());
        when(workspace.fiscalProfile(OWNER, TENANT)).thenReturn(COMPLETE);
        when(workspace.isOpened(OWNER, TENANT)).thenReturn(false);
        when(workspace.ownerSigner(OWNER)).thenReturn(Optional.of(new OwnerWorkspacePort.OwnerSigner("Ana García", "ana.verified@bistro.es")));
        when(workspace.summary(OWNER, TENANT)).thenReturn(new OwnerWorkspaceService.Summary(
                new SetupProgress(true, true, true, false), 5, RestaurantSettings.DEFAULTS,
                COMPLETE, null, true, true, false, false, false));
    }

    @Test
    void lockedNifShowsOwnerHowToMoveToAnotherCompany() throws Exception {
        when(workspace.fiscalLocked(OWNER, TENANT)).thenReturn(true);
        String html = mockMvc.perform(get("/owner/console?view=tickets&location=" + TENANT)
                        .locale(Locale.ENGLISH).with(user(ownerPrincipal())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("This location issued tickets under NIF B09767898")
                .contains("another company, open a new location")
                .contains("Open a new location")
                .contains("download this location’s backup and restore it in the new location")
                .contains("Open backup and restore")
                .contains("same taxpayer, contact support")
                .contains("mailto:info@brasatech.es")
                .contains("href=\"/owner/console?view=locations#add-location\"")
                .contains("href=\"/owner/console?view=data&amp;section=backup&amp;location=bistro\"")
                .contains("aria-describedby=\"taxId-status taxId-transition\"")
                .doesNotContain("id=\"taxId-hint\"");
        when(workspace.fiscalLocked(OWNER, TENANT)).thenReturn(false);
        String unlockedHtml = mockMvc.perform(get("/owner/console?view=tickets&location=" + TENANT)
                        .locale(Locale.ENGLISH).with(user(ownerPrincipal())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(unlockedHtml)
                .contains("id=\"taxId-hint\"")
                .contains("aria-describedby=\"taxId-hint taxId-status\"")
                .doesNotContain("id=\"taxId-transition\"");
        String locations = mockMvc.perform(get("/owner/console?view=locations").with(user(ownerPrincipal())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(locations).contains("id=\"add-location\"");
        String backup = mockMvc.perform(get("/owner/console?view=data&section=backup&location=" + TENANT).with(user(ownerPrincipal())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(backup).contains("backup");
        var staff = new TenantUser("cashier", "secret", true, List.of(new SimpleGrantedAuthority("ROLE_CASHIER")), TENANT);
        mockMvc.perform(get("/owner/console?view=tickets&location=" + TENANT).with(user(staff)))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void changedNifBeforeFirstTicketExplainsMissingAuthorization() throws Exception {
        when(workspace.fiscalLocked(OWNER, TENANT)).thenReturn(false);
        when(workspace.saveFiscalProfile(eq(OWNER), eq(TENANT), any())).thenReturn(java.util.Map.of());
        var result = mockMvc.perform(post("/owner/workspace/" + TENANT + "/legal")
                        .with(user(ownerPrincipal())).with(csrf()).locale(Locale.ENGLISH)
                        .param("legalName", "Bistro Ana SL").param("tradeName", "Bistro Ana")
                        .param("taxId", "12345678Z").param("addressLine", "Calle Mayor 1")
                        .param("postalCode", "28013").param("city", "Madrid").param("province", "Madrid")
                        .param("phone", "600123456").param("email", "ana@bistro.es")
                        .param("seriesPrefix", "BA").param("defaultVatRate", "10"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error")).andReturn();
        assertThat(result.getFlashMap().get("error")).asString().contains("authorisation for this NIF is not valid yet");
        String html = mockMvc.perform(get("/owner/console?view=location-settings&section=legal&location=" + TENANT)
                        .locale(Locale.ENGLISH).with(user(ownerPrincipal())).flashAttrs(result.getFlashMap()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("authorisation for this NIF is not valid yet");
    }

    @Test
    void changedNifWithExistingAuthorizationDistinguishesCollaborationState() throws Exception {
        when(workspace.saveFiscalProfile(eq(OWNER), eq(TENANT), any())).thenReturn(java.util.Map.of());
        when(verifactuAuthorizations.hasValidAuthorization("12345678Z")).thenReturn(true);
        var request = post("/owner/workspace/" + TENANT + "/legal").with(user(ownerPrincipal())).with(csrf())
                .locale(Locale.ENGLISH).param("taxId", "12345678Z").param("legalName", "Bistro Ana SL")
                .param("addressLine", "Calle Mayor 1").param("postalCode", "28013")
                .param("city", "Madrid").param("province", "Madrid").param("seriesPrefix", "BA");
        when(verifactuAuthorizations.isCollaborationActive()).thenReturn(false);
        var inactive = mockMvc.perform(request).andExpect(status().is3xxRedirection()).andReturn();
        assertThat(inactive.getFlashMap().get("error")).asString().contains("until AEAT collaboration is activated");
        when(verifactuAuthorizations.isCollaborationActive()).thenReturn(true);
        var active = mockMvc.perform(request).andExpect(status().is3xxRedirection()).andReturn();
        assertThat(active.getFlashMap().containsKey("error")).isFalse();
    }

    @Test
    void sameNifAfterTicketsCanSaveOtherDetailsWithoutNewAuthorizationWarning() throws Exception {
        when(workspace.fiscalLocked(OWNER, TENANT)).thenReturn(true);
        when(workspace.saveFiscalProfile(eq(OWNER), eq(TENANT), any())).thenReturn(java.util.Map.of());
        var result = mockMvc.perform(post("/owner/workspace/" + TENANT + "/legal")
                        .with(user(ownerPrincipal())).with(csrf()).locale(Locale.ENGLISH)
                        .param("taxId", "B09767898").param("legalName", "Updated Bistro")
                        .param("addressLine", "Calle Mayor 1").param("postalCode", "28013")
                        .param("city", "Madrid").param("province", "Madrid").param("seriesPrefix", "BA"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("notice")).andReturn();
        assertThat(result.getFlashMap().containsKey("error")).isFalse();
        verify(workspace).saveFiscalProfile(eq(OWNER), eq(TENANT), org.mockito.ArgumentMatchers.argThat(p -> "Updated Bistro".equals(p.legalName())));
        verify(verifactuAuthorizations, never()).hasValidAuthorization(anyString());
    }

    @Test
    void lockedNifPostRendersTheReasonAfterRedirect() throws Exception {
        when(workspace.fiscalLocked(OWNER, TENANT)).thenReturn(true);
        when(workspace.saveFiscalProfile(eq(OWNER), eq(TENANT), any())).thenReturn(java.util.Map.of("taxId", "fiscal.error.taxId.locked"));
        var result = mockMvc.perform(post("/owner/workspace/" + TENANT + "/legal")
                        .with(user(ownerPrincipal())).with(csrf())
                        .param("legalName", "Bistro Ana SL").param("tradeName", "Bistro Ana")
                        .param("taxId", "12345678Z").param("addressLine", "Calle Mayor 1")
                        .param("postalCode", "28013").param("city", "Madrid").param("province", "Madrid")
                        .param("phone", "600123456").param("email", "ana@bistro.es")
                        .param("seriesPrefix", "BA").param("defaultVatRate", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/owner/console?view=location-settings&section=legal&location=" + TENANT + "&fiscalError=1"))
                .andExpect(flash().attributeExists("fiscalDraft", "fiscalErrors"))
                .andReturn();
        String html = mockMvc.perform(get("/owner/console?view=location-settings&section=legal&location=" + TENANT + "&fiscalError=1")
                        .locale(Locale.ENGLISH).with(user(ownerPrincipal())).flashAttrs(result.getFlashMap()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Tickets were issued with this NIF, so it can’t change.");
        assertThat(html).contains("This location issued tickets under NIF B09767898")
                .contains("value=\"B09767898\"");
    }

    @Test
    void openingStepShowsFiscalDetailsPendingTrialAndRefreshesActiveRequestOnLoad() throws Exception {
        when(verifactuAuthorizations.isSigningConfigured()).thenReturn(true);
        when(verifactuAuthorizations.findCurrentForNif("B09767898")).thenReturn(Optional.empty());

        String html = mockMvc.perform(get("/owner/console?view=today&location=" + TENANT)
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        verify(verifactuAuthorizations).refreshForNifOnPageLoad("B09767898");
        assertThat(html).contains("Bistro Ana SL")
                .contains("B09767898")
                .contains("Firmar con certificado digital")
                .contains("La prueba empieza al abrir el local");
    }

    @Test
    void openedLocationDoesNotRefreshOnPageLoad() throws Exception {
        when(workspace.isOpened(OWNER, TENANT)).thenReturn(true);
        when(verifactuAuthorizations.isSigningConfigured()).thenReturn(true);
        when(verifactuAuthorizations.findCurrentForNif("B09767898")).thenReturn(Optional.empty());

        mockMvc.perform(get("/owner/console?view=today&location=" + TENANT)
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal())))
                .andExpect(status().isOk());

        verify(verifactuAuthorizations, never()).refreshForNifOnPageLoad(anyString());
        verify(verifactuAuthorizations, never()).refreshForNif(anyString());
    }

    @Test
    void authorizePostUsesOwnerAccountIdentityAndRefreshPostTriggersFullRefresh() throws Exception {
        VerifactuAuthorization auth = new VerifactuAuthorization(
                UUID.randomUUID(), "B09767898", "Bistro Ana SL",
                VerifactuAuthorizationStatus.IN_PROGRESS, "req-1", "verifactu-B09767898-1", 1,
                "tpl-1", "Ana García", null, null, null, null, null, null, OWNER,
                Instant.now(), Instant.now(), null, null, Instant.now(), 0, 0, null);
        when(verifactuAuthorizations.startSigning(eq(COMPLETE), eq(OWNER), eq("Ana García"), eq("ana.verified@bistro.es"), eq("es")))
                .thenReturn(StartSigningResult.signingUrl("https://digital-sign.example/sign/secret-token", auth));

        mockMvc.perform(post("/owner/workspace/" + TENANT + "/verifactu/authorize")
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "https://digital-sign.example/sign/secret-token"));

        mockMvc.perform(post("/owner/workspace/" + TENANT + "/verifactu/refresh")
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/owner/console?location=" + TENANT));

        verify(verifactuAuthorizations).refreshForNif("B09767898");
    }

    @Test
    void authorizeAndRefreshRefuseMissingCsrfAndForeignLocation() throws Exception {
        // Missing CSRF -> 403
        mockMvc.perform(post("/owner/workspace/" + TENANT + "/verifactu/authorize")
                        .with(user(ownerPrincipal())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/owner/workspace/" + TENANT + "/verifactu/refresh")
                        .with(user(ownerPrincipal())))
                .andExpect(status().isForbidden());

        // Foreign location -> 404, service never called
        doThrow(new IllegalArgumentException("You do not own this location."))
                .when(workspace).requireOwner(OWNER, "other");
        mockMvc.perform(post("/owner/workspace/other/verifactu/authorize")
                        .with(user(ownerPrincipal()))
                        .with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/owner/workspace/other/verifactu/refresh")
                        .with(user(ownerPrincipal()))
                        .with(csrf()))
                .andExpect(status().isNotFound());

        verify(verifactuAuthorizations, never()).startSigning(any(), anyString(), anyString(), anyString(), anyString());
        verify(verifactuAuthorizations, never()).refreshForNif(anyString());
    }

    @Test
    void authorizePostDoesNotFallbackToUnverifiedOrFiscalEmailWhenVerifiedEmailIsMissing() throws Exception {
        // Owner has no verified email, even though subscriptions.resolveOwnerEmail and fiscal.email() both have emails
        when(workspace.ownerSigner(OWNER)).thenReturn(Optional.of(new OwnerWorkspacePort.OwnerSigner("Ana García", null)));
        when(verifactuAuthorizations.startSigning(eq(COMPLETE), eq(OWNER), eq("Ana García"), isNull(), eq("es")))
                .thenReturn(StartSigningResult.unavailable("SIGNING_UNAVAILABLE"));

        mockMvc.perform(post("/owner/workspace/" + TENANT + "/verifactu/authorize")
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/owner/console?location=" + TENANT))
                .andExpect(flash().attributeExists("error"));

        verify(verifactuAuthorizations).startSigning(eq(COMPLETE), eq(OWNER), eq("Ana García"), isNull(), eq("es"));
    }

    @Test
    void testEnvironmentStatusShowsPlainReasonAndAllowsRetry() throws Exception {
        VerifactuAuthorization testEnv = new VerifactuAuthorization(
                UUID.randomUUID(), "B09767898", "Bistro Ana SL",
                VerifactuAuthorizationStatus.TEST_ENVIRONMENT, "req-test-1", "verifactu-B09767898-1", 1,
                "tpl-1", "Ana García", null, null, null, null, null, "TEST_ENVIRONMENT", OWNER,
                Instant.now(), Instant.now(), null, null, Instant.now(), 1, 0, null);
        when(verifactuAuthorizations.isSigningConfigured()).thenReturn(true);
        when(verifactuAuthorizations.refreshForNifOnPageLoad("B09767898")).thenReturn(Optional.of(testEnv));
        when(verifactuAuthorizations.findCurrentForNif("B09767898")).thenReturn(Optional.of(testEnv));

        mockMvc.perform(get("/owner/console").param("location", TENANT)
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Firmada en el entorno de pruebas de Digital Sign; no vale para producción.")))
                .andExpect(content().string(containsString("Firmar de nuevo")));
    }

    @Test
    void productionValidAuthorizationShowsRevokeDialogWithAllSharedLocationsAndNifInput() throws Exception {
        Instant now = Instant.now();
        VerifactuAuthorization valid = new VerifactuAuthorization(
                UUID.randomUUID(), "B09767898", "Bistro Ana SL",
                VerifactuAuthorizationStatus.VALID, "req-valid-1", "verifactu-B09767898-1", 1,
                "tpl-1", "Ana García", "B09767898", "B09767898/signed.pdf", "sha1",
                "B09767898/evidence.pdf", "sha2", null, OWNER,
                now, now, now, null, now, 1, 0, null);
        TenantLocation loc1 = new TenantLocation("1", OWNER, TENANT, "RESTAURANT", null, null, false, false, null);
        TenantLocation loc2 = new TenantLocation("2", OWNER, "bistronorte", "RESTAURANT", null, null, false, false, null);
        TenantLocation locOtherNif = new TenantLocation("3", OWNER, "otrolocal", "RESTAURANT", null, null, false, false, null);
        when(tenantLocationService.getLocationsByOwner(OWNER)).thenReturn(List.of(loc1, loc2, locOtherNif));
        when(workspace.fiscalProfile(OWNER, "bistronorte")).thenReturn(COMPLETE);
        when(workspace.fiscalProfile(OWNER, "otrolocal")).thenReturn(new FiscalProfile(
                "Otro SL", "Otro", "12345678Z", "Calle Sol 2", "28013", "Madrid", "Madrid",
                "600000000", "otro@bistro.es", "OT", new BigDecimal("10")));
        when(verifactuAuthorizations.isSigningConfigured()).thenReturn(true);
        when(verifactuAuthorizations.isCollaborationActive()).thenReturn(true);
        when(verifactuAuthorizations.findCurrentForNif("B09767898")).thenReturn(Optional.of(valid));

        String html = mockMvc.perform(get("/owner/console").param("location", TENANT)
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("Revocar autorización")
                .contains("id=\"verifactu-revoke-dialog\"")
                .contains("id=\"verifactu-revoke-nif-input\"")
                .contains("data-expected-nif=\"B09767898\"")
                .contains("Todos estos locales quedarán en pausa de inmediato")
                .contains("<li>bistro</li>")
                .contains("<li>bistronorte</li>")
                .doesNotContain("<li>otrolocal</li>")
                .doesNotContain("firma de prueba");
        assertThat(StandaloneWorkspaceLeakTest.extractReadableText(html)).doesNotContain("sandbox");
    }

    @Test
    void productionRevokeRequiresCsrfOwnerAndMatchingNifAndThenShowsRetryButton() throws Exception {
        // Missing CSRF -> 403
        mockMvc.perform(post("/owner/workspace/" + TENANT + "/verifactu/revoke")
                        .param("confirmNif", "B09767898")
                        .with(user(ownerPrincipal())))
                .andExpect(status().isForbidden());

        // Foreign location -> 404
        doThrow(new IllegalArgumentException("You do not own this location."))
                .when(workspace).requireOwner(OWNER, "other");
        mockMvc.perform(post("/owner/workspace/other/verifactu/revoke")
                        .param("confirmNif", "B09767898")
                        .with(user(ownerPrincipal()))
                        .with(csrf()))
                .andExpect(status().isNotFound());

        // Missing or wrong confirmNif in production -> rejected without calling revoke
        mockMvc.perform(post("/owner/workspace/" + TENANT + "/verifactu/revoke")
                        .param("confirmNif", "12345678Z")
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/owner/console?location=" + TENANT))
                .andExpect(flash().attribute("error", containsString("El NIF escrito no coincide")));
        verify(verifactuAuthorizations, never()).revoke(anyString(), anyString());

        // Matching NIF (case/hyphen normalized) -> revokes and redirects with notice
        Instant now = Instant.now();
        VerifactuAuthorization revoked = new VerifactuAuthorization(
                UUID.randomUUID(), "B09767898", "Bistro Ana SL",
                VerifactuAuthorizationStatus.REVOKED, "req-valid-1", "verifactu-B09767898-1", 1,
                "tpl-1", "Ana García", "B09767898", "B09767898/signed.pdf", "sha1",
                "B09767898/evidence.pdf", "sha2", null, OWNER,
                now.minusSeconds(60), now, now.minusSeconds(60), now, OWNER, now, 2, 0, null);
        when(verifactuAuthorizations.revoke("B09767898", OWNER)).thenReturn(RevokeResult.revoked(revoked));

        mockMvc.perform(post("/owner/workspace/" + TENANT + "/verifactu/revoke")
                        .param("confirmNif", "b-09767898")
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/owner/console?location=" + TENANT))
                .andExpect(flash().attribute("notice", containsString("Autorización VERI*FACTU revocada")));
        verify(verifactuAuthorizations).revoke("B09767898", OWNER);

        // After revoking, overview offers "Firmar de nuevo" and hides revoke button
        when(verifactuAuthorizations.isSigningConfigured()).thenReturn(true);
        when(verifactuAuthorizations.findCurrentForNif("B09767898")).thenReturn(Optional.of(revoked));
        String afterHtml = mockMvc.perform(get("/owner/console").param("location", TENANT)
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(afterHtml)
                .contains("Firmar de nuevo")
                .doesNotContain("data-verifactu-revoke");
    }
}
