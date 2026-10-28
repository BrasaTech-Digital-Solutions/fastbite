package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.office.UserService;
import es.brasatech.fastbite.application.platform.PlatformAdmins;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.platform.PromotionException;
import es.brasatech.fastbite.application.platform.PromotionService;
import es.brasatech.fastbite.application.platform.PromotionService.PromotionResult;
import es.brasatech.fastbite.application.platform.PromotionService.UndoResult;
import es.brasatech.fastbite.application.platform.PromotionService.UndoablePromotionInfo;
import es.brasatech.fastbite.application.settings.GuestPrivacyNoticeService;
import es.brasatech.fastbite.application.tenant.*;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort.PromotionPreview;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.ThemeCatalog;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.security.TenantUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {OwnerWorkspaceController.class}, properties = "spring.messages.basename=i18n/messages")
@ContextConfiguration(classes = {TestConfig.class, OwnerWorkspaceController.class,
        PlatformEnvironment.class, PlatformLink.class, PlatformAdmins.class, ThemeCatalog.class,
        SecurityConfig.class, TenantRoutingResolver.class, WebConfig.class})
class OwnerPromotionEndpointTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRoutingResolver routingResolver;

    @MockitoBean TenantLocationService tenantLocationService;
    @MockitoBean OwnerWorkspaceService workspace;
    @MockitoBean OwnerStaffService staff;
    @MockitoBean SubscriptionService subscriptions;
    @MockitoBean TenantBackupRestorePort backups;
    @MockitoBean AppearancePort appearance;
    @MockitoBean FreePlanLock freePlanLock;
    @MockitoBean LegalAcceptancePort acceptances;
    @MockitoBean GuestPrivacyNoticeService privacyNotices;
    @MockitoBean UserService userService;
    @MockitoBean PromotionService promotionService;

    private static final String TENANT = "promorestaurant";
    private static final String OWNER = "ana";

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
    }

    @Test
    void previewReturns200WithStructuredDiffAndToken() throws Exception {
        PromotionPreview preview = new PromotionPreview(
                "token-abc-123",
                PromotionPreview.EntityDiff.empty(),
                PromotionPreview.EntityDiff.empty(),
                PromotionPreview.EntityDiff.empty(),
                PromotionPreview.EntityDiff.empty(),
                PromotionPreview.EntityDiff.empty(),
                new PromotionPreview.ThemeDiff(null, null),
                true,
                List.of(),
                2,
                1793188800000L
        );
        when(promotionService.preview(TENANT, OWNER)).thenReturn(preview);

        mockMvc.perform(post("/owner/workspace/{tenant}/promote/preview", TENANT)
                        .with(user(ownerPrincipal()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("token-abc-123"))
                .andExpect(jsonPath("$.pricesOverwritten").value(2));
    }

    @Test
    void previewMapsErrorsToCodeAndMessage() throws Exception {
        when(promotionService.preview(TENANT, OWNER))
                .thenThrow(new PromotionException("promotion.sandbox.empty", 422, "No menu to promote"));

        mockMvc.perform(post("/owner/workspace/{tenant}/promote/preview", TENANT)
                        .locale(Locale.forLanguageTag("es"))
                        .with(user(ownerPrincipal()))
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("promotion.sandbox.empty"))
                .andExpect(jsonPath("$.message").value("El local del entorno de pruebas aún no tiene carta para promocionar. Añade platos en el entorno de pruebas primero."));

        doThrow(new PromotionException("promotion.sandbox.other-version", 409, "Version mismatch"))
                .when(promotionService).preview(TENANT, OWNER);

        mockMvc.perform(post("/owner/workspace/{tenant}/promote/preview", TENANT)
                        .with(user(ownerPrincipal()))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("promotion.sandbox.other-version"));
    }

    @Test
    void confirmWithTokenReturnsApplied() throws Exception {
        when(promotionService.confirm(TENANT, OWNER, "my-token"))
                .thenReturn(new PromotionResult("id-1", "Applied summary", "snapshot-1.zip"));

        mockMvc.perform(post("/owner/workspace/{tenant}/promote/confirm", TENANT)
                        .with(user(ownerPrincipal()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"my-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("applied"))
                .andExpect(jsonPath("$.snapshot").value("snapshot-1.zip"));
    }

    @Test
    void confirmWithoutTokenReturns400() throws Exception {
        mockMvc.perform(post("/owner/workspace/{tenant}/promote/confirm", TENANT)
                        .with(user(ownerPrincipal()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("promotion.token.invalid"));
    }

    @Test
    void confirmWithInvalidTokenReturns409() throws Exception {
        when(promotionService.confirm(TENANT, OWNER, "expired-token"))
                .thenThrow(new PromotionException("promotion.token.invalid", 409, "Invalid or expired token"));

        mockMvc.perform(post("/owner/workspace/{tenant}/promote/confirm", TENANT)
                        .with(user(ownerPrincipal()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"expired-token\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("promotion.token.invalid"));
    }

    @Test
    void undoReturnsUndoneStatus() throws Exception {
        when(promotionService.undo(TENANT, OWNER))
                .thenReturn(new UndoResult("id-1", "snapshot-1.zip"));

        mockMvc.perform(post("/owner/workspace/{tenant}/promote/undo", TENANT)
                        .with(user(ownerPrincipal()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("undone"))
                .andExpect(jsonPath("$.snapshot").value("snapshot-1.zip"));
    }

    @Test
    void backupSectionIncludesUndoablePromotionModel() throws Exception {
        when(promotionService.getUndoablePromotion(TENANT, OWNER))
                .thenReturn(Optional.of(new UndoablePromotionInfo("id-1", Instant.parse("2026-10-28T09:00:00Z"), "5 dishes promoted")));

        mockMvc.perform(get("/owner/console")
                        .param("view", "location-settings")
                        .param("section", "backup")
                        .param("location", TENANT)
                        .with(user(ownerPrincipal())))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("undoablePromotion"));
    }
}
