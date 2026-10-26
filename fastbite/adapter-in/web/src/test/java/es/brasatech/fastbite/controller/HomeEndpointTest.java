package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.VerifactuDeclaration;
import es.brasatech.fastbite.application.verifactu.VerifactuSystem;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HomeController.class)
@ContextConfiguration(classes = {TestConfig.class, HomeController.class, SecurityConfig.class,
        TenantRoutingResolver.class, WebConfig.class, PlatformOfferAdvice.class, EnvironmentAdvice.class})
@org.springframework.test.context.TestPropertySource(properties = "spring.messages.basename=i18n/messages")
class HomeEndpointTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private VerifactuDeclaration declaration;
    @MockitoBean private TenantLocationService tenantLocationService;
    @MockitoBean private es.brasatech.fastbite.application.tenant.SubscriptionService subscriptions;

    private static final VerifactuSystem SYSTEM = new VerifactuSystem("Brasatech Digital SL", "B12345674", "FastBite",
            "FB", "1.0", "Servicios de restauración");

    @BeforeEach
    void setUp() {
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        when(declaration.system()).thenReturn(SYSTEM);
        when(subscriptions.platformSettings()).thenReturn(new es.brasatech.fastbite.domain.tenant.PlatformBillingSettings(4900, 30, 0));
    }

    @Test
    void spanishBrowserGetsVerifactuReadyWhenUnsigned() throws Exception {
        when(declaration.signed()).thenReturn(false);

        mockMvc.perform(get("/").locale(Locale.forLanguageTag("es")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("preparado para VERI*FACTU"),
                        containsString("Envío automático a la AEAT"),
                        containsString("1 de enero de 2027 para los contribuyentes del Impuesto sobre Sociedades"),
                        containsString("País Vasco (TicketBAI), Navarra, Canarias (IGIC), Ceuta ni Melilla"),
                        not(containsString("cumple VERI*FACTU")),
                        not(containsString("certificado")),
                        not(containsString("homologado")),
                        not(containsString("al instante"))
                )));
    }

    @Test
    void spanishBrowserGetsCumpleWhenSignedAndConfigured() throws Exception {
        when(declaration.signed()).thenReturn(true);

        mockMvc.perform(get("/").locale(Locale.forLanguageTag("es")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("cumple VERI*FACTU"),
                        containsString("/verifactu/declaracion-responsable"),
                        not(containsString("preparado para VERI*FACTU")),
                        not(containsString("certificado")),
                        not(containsString("homologado")),
                        not(containsString("al instante"))
                )));
    }

    @Test
    void englishBrowserGetsEnglishWording() throws Exception {
        when(declaration.signed()).thenReturn(false);

        mockMvc.perform(get("/").locale(Locale.ENGLISH))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Restaurant POS prepared for VERI*FACTU"),
                        containsString("Automatic submission to AEAT"),
                        not(containsString("VERI*FACTU-compliant")),
                        not(containsString("certified")),
                        not(containsString("homologated")),
                        not(containsString("approved by AEAT"))
                )));

        when(declaration.signed()).thenReturn(true);

        mockMvc.perform(get("/").locale(Locale.ENGLISH))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("VERI*FACTU-compliant"),
                        containsString("/verifactu/declaracion-responsable"),
                        not(containsString("prepared for VERI*FACTU"))
                )));
    }

    @Test
    void portugueseBrowserGetsPortugueseWording() throws Exception {
        when(declaration.signed()).thenReturn(false);

        mockMvc.perform(get("/").locale(Locale.forLanguageTag("pt")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("preparado para o VERI*FACTU"),
                        containsString("Envio automático para a AEAT")
                )));
    }

    @Test
    void unsignedHomeInAllLanguagesContainsNoComplianceClaims() throws Exception {
        when(declaration.signed()).thenReturn(false);

        String[] forbidden = {
                "cumple", "cumplas", "conforme a la normativa",
                "compliant", "compliance",
                "cumprir", "cumpre",
                "certificado", "homologado", "al instante",
                "certified", "homologated", "approved by aeat"
        };

        for (Locale locale : List.of(Locale.forLanguageTag("es"), Locale.ENGLISH, Locale.forLanguageTag("pt"))) {
            String page = mockMvc.perform(get("/").locale(locale))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString().toLowerCase(Locale.ROOT);

            for (String phrase : forbidden) {
                assertFalse(
                        page.contains(phrase.toLowerCase(Locale.ROOT)),
                        () -> "Locale " + locale + " rendered forbidden phrase: '" + phrase + "'"
                );
            }
        }
    }
}
