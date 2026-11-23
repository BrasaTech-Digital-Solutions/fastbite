package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.security.TenantUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies {@code fragments/errorPage.html} across 404 (page and unknown restaurant), 403 and 500,
 * in sandbox and production standalone, in es / en / pt, both logged in and anonymous.
 */
class ErrorPageMatrixTest {

    static final Map<String, String> TITLE_404 = Map.of(
            "es", "No encontramos esta página",
            "en", "We couldn&#39;t find this page",
            "pt", "Não encontrámos esta página");
    static final Map<String, String> TITLE_UNKNOWN = Map.of(
            "es", "No encontramos este restaurante",
            "en", "We couldn&#39;t find this restaurant",
            "pt", "Não encontrámos este restaurante");
    static final Map<String, String> TITLE_403 = Map.of(
            "es", "No tienes acceso a esta página",
            "en", "This page isn&#39;t available to you",
            "pt", "Esta página não está disponível para si");
    static final Map<String, String> TITLE_500 = Map.of(
            "es", "Algo ha fallado por nuestra parte",
            "en", "Something went wrong on our side",
            "pt", "Algo correu mal do nosso lado");

    @Controller
    static class ErrorViewStubController {
        @GetMapping("/legal/test-error")
        String render(@RequestParam String view,
                      @RequestParam(required = false) String tenantId,
                      @RequestParam(required = false) Boolean unknown,
                      @RequestParam(required = false) String timestamp,
                      Model model) {
            if (tenantId != null) model.addAttribute("tenantId", tenantId);
            if (Boolean.TRUE.equals(unknown)) model.addAttribute("unknownRestaurant", "ghost");
            if (timestamp != null) model.addAttribute("timestamp", timestamp);
            return "error/" + view;
        }
    }

    @WebMvcTest(controllers = ErrorViewStubController.class, properties = "spring.messages.basename=i18n/messages")
    @ContextConfiguration(classes = {
            TestConfig.class, ErrorViewStubController.class,
            EnvironmentAdvice.class, PlatformEnvironment.class, PlatformLink.class,
            SecurityConfig.class, TenantRoutingResolver.class, WebConfig.class
    })
    abstract static class MatrixBase {
        @Autowired MockMvc mockMvc;
        @MockitoBean TenantLocationService tenantLocationService;

        @BeforeEach
        void setUp() {
            when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        }

        abstract boolean expectSandboxBanner();

        TenantUser staffUser() {
            return new TenantUser("cashier", "hash", true,
                    List.of(new SimpleGrantedAuthority("ROLE_CASHIER")), "kebab");
        }

        String renderError(String view, String tenantId, boolean unknown, String timestamp, Locale locale, boolean loggedIn)
                throws Exception {
            var req = get("/legal/test-error").param("view", view).locale(locale);
            if (tenantId != null) req.param("tenantId", tenantId);
            if (unknown) req.param("unknown", "true");
            if (timestamp != null) req.param("timestamp", timestamp);
            if (loggedIn) req.with(user(staffUser()));
            return mockMvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        }

        void assertCleanMarkup(String html, String lang) {
            assertThat(html).contains("lang=\"" + lang + "\"");
            assertThat(html).doesNotContain("/webjars/bootstrap", "/webjars/font-awesome", "fas fa-", "/css/menu.css", "style=");
            assertThat(html).contains("mailto:info@brasatech.es");
            if (expectSandboxBanner()) {
                assertThat(html).contains("fb-sandbox-banner");
            } else {
                assertThat(html).doesNotContain("fb-sandbox-banner");
                assertThat(StandaloneWorkspaceLeakTest.extractReadableText(html)).doesNotContain("sandbox");
            }
        }

        @Test
        void renders404UnknownAndKnownPageInAllLocalesAndAuthStates() throws Exception {
            for (String lang : List.of("es", "en", "pt")) {
                Locale locale = Locale.forLanguageTag(lang);
                for (boolean loggedIn : List.of(false, true)) {
                    String page404 = renderError("404", "kebab", false, null, locale, loggedIn);
                    assertCleanMarkup(page404, lang);
                    assertThat(page404).contains(TITLE_404.get(lang));
                    assertThat(page404).contains("href=\"/kebab/menu\"");
                    assertThat(page404).doesNotContain("/kebab/login");

                    String unknown404 = renderError("404", "ghost", true, null, locale, loggedIn);
                    assertCleanMarkup(unknown404, lang);
                    assertThat(unknown404).contains(TITLE_UNKNOWN.get(lang));
                    assertThat(unknown404).contains("href=\"/\"");
                }
            }
        }

        @Test
        void renders403InAllLocalesAndAuthStates() throws Exception {
            for (String lang : List.of("es", "en", "pt")) {
                Locale locale = Locale.forLanguageTag(lang);
                for (boolean loggedIn : List.of(false, true)) {
                    String html = renderError("403", "kebab", false, null, locale, loggedIn);
                    assertCleanMarkup(html, lang);
                    assertThat(html).contains(TITLE_403.get(lang));
                    assertThat(html).contains("href=\"/kebab/menu\"");
                    assertThat(html).contains("href=\"/kebab/login\"");
                }
            }
        }

        @Test
        void renders500InAllLocalesAndAuthStates() throws Exception {
            for (String lang : List.of("es", "en", "pt")) {
                Locale locale = Locale.forLanguageTag(lang);
                for (boolean loggedIn : List.of(false, true)) {
                    String html = renderError("5xx", "kebab", false, "2026-11-23 15:00:00", locale, loggedIn);
                    assertCleanMarkup(html, lang);
                    assertThat(html).contains(TITLE_500.get(lang));
                    assertThat(html).contains("2026-11-23 15:00:00");
                    assertThat(html).contains("href=\"/kebab/menu\"");
                }
            }
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "fastbite.environment=sandbox",
            "fastbite.link.url=https://fastbite.example",
            "fastbite.link.secret=0123456789abcdef0123456789abcdef"
    })
    class Sandbox extends MatrixBase {
        @Override
        boolean expectSandboxBanner() {
            return true;
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "fastbite.environment=production",
            "fastbite.link.url=",
            "fastbite.link.standalone=true"
    })
    class ProductionStandalone extends MatrixBase {
        @Override
        boolean expectSandboxBanner() {
            return false;
        }
    }
}
