package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.VerifactuDeclaration;
import es.brasatech.fastbite.application.verifactu.VerifactuSystem;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The sandbox can never be mistaken for production: every page of it says so. */
class EnvironmentBannerTest {

    static final VerifactuSystem SYSTEM = new VerifactuSystem("Brasatech Digital SL", "B12345674", "FastBite",
            "FB", "1.0", "Servicios de restauración");

    @WebMvcTest(controllers = DeclarationController.class)
    @ContextConfiguration(classes = {TestConfig.class, DeclarationController.class, es.brasatech.fastbite.service.ClasspathDeclarationArchive.class, EnvironmentAdvice.class,
            SecurityConfig.class, TenantRoutingResolver.class, WebConfig.class})
    abstract static class Page {
        @Autowired MockMvc mockMvc;
        @MockitoBean VerifactuDeclaration declaration;
        @MockitoBean TenantLocationService tenantLocationService;

        @BeforeEach
        void setUp() {
            when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
            when(declaration.system()).thenReturn(SYSTEM);
            when(declaration.missing()).thenReturn(java.util.List.of());
        }

        String page() throws Exception {
            return mockMvc.perform(get("/verifactu/declaracion-responsable"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        }
    }

    @Nested
    @TestPropertySource(properties = {"fastbite.environment=sandbox", "fastbite.link.url=https://fastbite.example"})
    class Sandbox extends Page {
        @Test
        void everyPageCarriesTheBannerAndALinkToProduction() throws Exception {
            String html = page();
            org.assertj.core.api.Assertions.assertThat(html).contains("fb-sandbox-banner", "sandbox.banner.label",
                    "href=\"https://fastbite.example\"", "favicon-sandbox.svg", "[Sandbox] ");
        }
    }

    @Nested
    @TestPropertySource(properties = {"fastbite.environment=sandbox", "fastbite.link.url="})
    class StandaloneSandbox extends Page {
        @Test
        void theBannerHasNoLinkToProduction() throws Exception {
            String html = page();
            org.assertj.core.api.Assertions.assertThat(html).contains("fb-sandbox-banner").doesNotContain("sandbox.banner.back");
        }
    }

    @Nested
    @TestPropertySource(properties = {"fastbite.environment=production", "fastbite.link.url=https://sandbox.example"})
    class Production extends Page {
        @Test
        void productionPagesHaveNoBanner() throws Exception {
            org.assertj.core.api.Assertions.assertThat(page()).doesNotContain("fb-sandbox-banner");
        }
    }
}
