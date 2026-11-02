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
import es.brasatech.fastbite.application.tenant.OwnerWorkspaceService;
import es.brasatech.fastbite.application.tenant.SubscriptionService;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.ThemeCatalog;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
import es.brasatech.fastbite.domain.tenant.SetupProgress;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.security.TenantUser;
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

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards standalone mode and unlinked sandbox against leaking sandbox or production promotion wording.
 */
class StandaloneWorkspaceLeakTest {

    static final String OWNER = "ana";
    static final String TENANT = "lacasita";

    static final List<String> FORBIDDEN_SPANISH = List.of(
            "sandbox", "a producción", "para producción", "de producción",
            "pasará a producción", "pasan a producción", "paquete de producción",
            "promoción", "promocionar", "llevar a producción", "abrir en fastbite",
            "traer configuración", "trae tu configuración", "trae tu carta"
    );

    static final List<String> FORBIDDEN_ENGLISH = List.of(
            "sandbox", "to production", "for production", "production bundle",
            "promot", "take to production", "open in fastbite", "bring your", "bring in"
    );

    static final List<String> TARGET_PHRASES_SPANISH = List.of(
            "a producción", "para producción", "de producción",
            "pasará a producción", "pasan a producción", "paquete de producción",
            "promoción", "promocionar", "llevar a producción", "abrir en fastbite",
            "traer configuración", "trae tu configuración", "trae tu carta"
    );

    static final List<String> TARGET_PHRASES_ENGLISH = List.of(
            "to production", "for production", "production bundle",
            "promot", "take to production", "open in fastbite", "bring your", "bring in"
    );

    static String extractReadableText(String html) {
        String cleaned = Pattern.compile("<script[^>]*>.*?</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(html).replaceAll(" ");
        cleaned = Pattern.compile("<style[^>]*>.*?</style>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(cleaned).replaceAll(" ");

        StringBuilder sb = new StringBuilder();
        Pattern attrPattern = Pattern.compile("(?:title|aria-label|placeholder|alt|content|data-[a-zA-Z0-9-]+)\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
        Matcher m = attrPattern.matcher(cleaned);
        while (m.find()) {
            sb.append(m.group(1)).append(" ");
        }
        String innerText = Pattern.compile("<[^>]+>").matcher(cleaned).replaceAll(" ");
        sb.append(innerText);
        return sb.toString().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    @WebMvcTest(controllers = OwnerWorkspaceController.class, properties = "spring.messages.basename=i18n/messages")
    @ContextConfiguration(classes = {
            TestConfig.class, OwnerWorkspaceController.class,
            EnvironmentAdvice.class, PlatformEnvironment.class, PlatformLink.class,
            PlatformAdmins.class, ThemeCatalog.class, SecurityConfig.class,
            TenantRoutingResolver.class, WebConfig.class
    })
    abstract static class BasePage {
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

        TenantUser ownerPrincipal() {
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
            when(subscriptions.account(anyString(), anyString())).thenReturn(null);
            when(staff.listStaff(anyString(), anyString())).thenReturn(List.of());
            when(backups.getAvailableTemplates(any())).thenReturn(List.of());
            when(freePlanLock.locked(anyString())).thenReturn(false);
            when(acceptances.mayReceiveOffers(anyString())).thenReturn(false);
            when(promotionService.getUndoablePromotion(anyString(), anyString())).thenReturn(Optional.empty());
        }

        String page(String url, Locale locale) throws Exception {
            return mockMvc.perform(get(url)
                            .locale(locale)
                            .with(user(ownerPrincipal())))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "fastbite.environment=production",
            "fastbite.link.url=",
            "fastbite.link.standalone=true"
    })
    class StandaloneProduction extends BasePage {

        @BeforeEach
        void setUpLocation() {
            OwnerWorkspaceService.Summary summary = new OwnerWorkspaceService.Summary(
                    SetupProgress.EMPTY, 0, RestaurantSettings.DEFAULTS, FiscalProfile.EMPTY, null, false, false, false
            );
            when(workspace.summary(OWNER, TENANT)).thenReturn(summary);
        }

        @Test
        void openingChecklistContainsNoForbiddenPhrasesInSpanishAndEnglish() throws Exception {
            for (Locale locale : List.of(Locale.forLanguageTag("es"), Locale.ENGLISH)) {
                String html = page("/owner/console?view=today&location=" + TENANT, locale);
                String readable = extractReadableText(html);
                List<String> forbidden = locale.getLanguage().equals("es") ? FORBIDDEN_SPANISH : FORBIDDEN_ENGLISH;
                for (String phrase : forbidden) {
                    assertThat(readable).as("view=today in %s must not contain '%s'", locale, phrase).doesNotContain(phrase);
                }
            }

            String esHtml = page("/owner/console?view=today&location=" + TENANT, Locale.forLanguageTag("es"));
            assertThat(extractReadableText(esHtml)).contains("crea tu carta");

            String enHtml = page("/owner/console?view=today&location=" + TENANT, Locale.ENGLISH);
            assertThat(extractReadableText(enHtml)).contains("build your menu");
        }

        @Test
        void allWorkspaceViewsContainNoForbiddenPhrases() throws Exception {
            List<String> views = List.of(
                    "/owner/console?view=menu&location=" + TENANT,
                    "/owner/console?view=team&location=" + TENANT,
                    "/owner/console?view=location-settings&section=backup&location=" + TENANT,
                    "/owner/console?view=location-settings&section=advanced&location=" + TENANT,
                    "/owner/console?view=locations",
                    "/owner/console?view=account",
                    "/owner/console?view=billing"
            );

            for (String url : views) {
                for (Locale locale : List.of(Locale.forLanguageTag("es"), Locale.ENGLISH)) {
                    String html = page(url, locale);
                    String readable = extractReadableText(html);
                    List<String> forbidden = locale.getLanguage().equals("es") ? FORBIDDEN_SPANISH : FORBIDDEN_ENGLISH;
                    for (String phrase : forbidden) {
                        assertThat(readable).as("%s in %s must not contain '%s'", url, locale, phrase).doesNotContain(phrase);
                    }
                }
            }
        }

        @Test
        void backupPanelUsesStandaloneCopyAndOmitsExportBundle() throws Exception {
            String esHtml = page("/owner/console?view=location-settings&section=backup&location=" + TENANT, Locale.forLanguageTag("es"));
            assertThat(extractReadableText(esHtml)).contains("copia de seguridad de fastbite");
            assertThat(esHtml).doesNotContain("export-production-bundle");

            String enHtml = page("/owner/console?view=location-settings&section=backup&location=" + TENANT, Locale.ENGLISH);
            assertThat(extractReadableText(enHtml)).contains("fastbite backup");
            assertThat(enHtml).doesNotContain("export-production-bundle");
        }

        @Test
        void bodyOmitsPromotionAttributesAndUsesStandaloneVersionMismatch() throws Exception {
            String html = page("/owner/console?view=today&location=" + TENANT, Locale.forLanguageTag("es"));
            assertThat(html).doesNotContain("data-demo-sandbox-only");
            assertThat(html).doesNotContain("data-promotion-success");
            assertThat(html).doesNotContain("data-undo-success");
            assertThat(html).doesNotContain("data-service-replaced");
            assertThat(html).contains("data-version-mismatch=\"Esta copia de seguridad");
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "fastbite.environment=sandbox",
            "fastbite.link.url="
    })
    class StandaloneSandbox extends BasePage {

        @BeforeEach
        void setUpTour() {
            OwnerWorkspaceService.Summary summary = new OwnerWorkspaceService.Summary(
                    new SetupProgress(true, false, false, false), 5, RestaurantSettings.DEFAULTS, FiscalProfile.EMPTY, null, false, false, true
            );
            when(workspace.summary(OWNER, TENANT)).thenReturn(summary);
        }

        @Test
        void practiceTourRendersWithStandaloneWordingInSpanishAndEnglish() throws Exception {
            String esHtml = page("/owner/console?view=today&location=" + TENANT, Locale.forLanguageTag("es"));
            assertThat(esHtml).contains("Cambia nombres, precios y fotos, y borra lo que no vendas.")
                    .doesNotContain("Esto es lo que pasará a producción.")
                    .contains("Crea cuentas de práctica para que tu equipo ensaye.")
                    .doesNotContain("No pasan a producción.");

            String enHtml = page("/owner/console?view=today&location=" + TENANT, Locale.ENGLISH);
            assertThat(enHtml).contains("Change names, prices and photos, and delete items you don’t sell.")
                    .doesNotContain("This is what will go to production.")
                    .contains("Create practice accounts for your team to rehearse.")
                    .doesNotContain("They do not pass to production.");
        }

        @Test
        void pagesContainNoTargetPhrasesAndNoProductionLinks() throws Exception {
            List<String> urls = List.of(
                    "/owner/console?view=today&location=" + TENANT,
                    "/owner/console?view=location-settings&section=backup&location=" + TENANT,
                    "/owner/console?view=location-settings&section=advanced&location=" + TENANT
            );

            for (String url : urls) {
                for (Locale locale : List.of(Locale.forLanguageTag("es"), Locale.ENGLISH)) {
                    String html = page(url, locale);
                    String readable = extractReadableText(html);
                    List<String> targets = locale.getLanguage().equals("es") ? TARGET_PHRASES_SPANISH : TARGET_PHRASES_ENGLISH;
                    for (String phrase : targets) {
                        assertThat(readable).as("%s in %s must not contain target phrase '%s'", url, locale, phrase).doesNotContain(phrase);
                    }
                }
            }

            String todayHtml = page("/owner/console?view=today&location=" + TENANT, Locale.ENGLISH);
            assertThat(todayHtml).doesNotContain("sandbox.banner.back")
                    .doesNotContain("href=\"https://");

            String backupHtml = page("/owner/console?view=location-settings&section=backup&location=" + TENANT, Locale.ENGLISH);
            assertThat(backupHtml).doesNotContain("export-production-bundle");
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "fastbite.environment=production",
            "fastbite.link.url=https://sandbox.example",
            "fastbite.link.secret=0123456789abcdef0123456789abcdef"
    })
    class LinkedProduction extends BasePage {

        @BeforeEach
        void setUpLocation() {
            OwnerWorkspaceService.Summary summary = new OwnerWorkspaceService.Summary(
                    new SetupProgress(true, false, false, false), 0, RestaurantSettings.DEFAULTS, FiscalProfile.EMPTY, null, false, false, false
            );
            when(workspace.summary(OWNER, TENANT)).thenReturn(summary);
        }

        @Test
        void openingStepAndJsAttributesRetainLinkedWording() throws Exception {
            String esHtml = page("/owner/console?view=today&location=" + TENANT, Locale.forLanguageTag("es"));
            assertThat(esHtml).contains("Trae tu configuración");
            assertThat(esHtml).contains("Trae tu carta del sandbox");
            assertThat(esHtml).contains("data-demo-sandbox-only");
            assertThat(esHtml).contains("data-promotion-success");

            String enBackup = page("/owner/console?view=location-settings&section=backup&location=" + TENANT, Locale.ENGLISH);
            assertThat(enBackup).contains("Bring in your sandbox setup");
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "fastbite.environment=sandbox",
            "fastbite.link.url=https://fastbite.example",
            "fastbite.link.secret=0123456789abcdef0123456789abcdef"
    })
    class LinkedSandbox extends BasePage {

        @BeforeEach
        void setUpTour() {
            OwnerWorkspaceService.Summary summary = new OwnerWorkspaceService.Summary(
                    new SetupProgress(true, false, false, false), 5, RestaurantSettings.DEFAULTS, FiscalProfile.EMPTY, null, false, false, true
            );
            when(workspace.summary(OWNER, TENANT)).thenReturn(summary);
        }

        @Test
        void practiceTourKeepsProductionWordingAndLinks() throws Exception {
            String esHtml = page("/owner/console?view=today&location=" + TENANT, Locale.forLanguageTag("es"));
            assertThat(esHtml).contains("Esto es lo que pasará a producción.");
            assertThat(esHtml).contains("No pasan a producción.");
            assertThat(esHtml).contains("href=\"https://fastbite.example\"");

            String enHtml = page("/owner/console?view=today&location=" + TENANT, Locale.ENGLISH);
            assertThat(enHtml).contains("This is what will go to production.");
            assertThat(enHtml).contains("They do not pass to production.");
        }

        @Test
        void exportPanelShowsInLinkedSandbox() throws Exception {
            String html = page("/owner/console?view=location-settings&section=backup&location=" + TENANT, Locale.ENGLISH);
            assertThat(html).contains("Sandbox-to-Production Promotion");
            assertThat(html).contains("export-production-bundle");
        }
    }
}
