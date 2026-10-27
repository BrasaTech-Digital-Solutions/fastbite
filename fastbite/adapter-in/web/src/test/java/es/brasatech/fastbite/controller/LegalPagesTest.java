package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.tenant.TenantRegistrationRules;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FastBite's legal pages in its three languages, and each restaurant's privacy notice for its guests. */
@WebMvcTest(controllers = LegalController.class)
@ContextConfiguration(classes = {TestConfig.class, LegalController.class, EnvironmentAdvice.class, SecurityConfig.class,
        TenantRoutingResolver.class, WebConfig.class,
        es.brasatech.fastbite.application.tenant.MarketingUnsubscribeLink.class})
@org.springframework.test.context.TestPropertySource(properties = "fastbite.jobs.secret=test-jobs-secret-at-least-32-characters-long")
class LegalPagesTest {

    @Autowired MockMvc mockMvc;
    @Autowired es.brasatech.fastbite.application.tenant.MarketingUnsubscribeLink unsubscribeLinks;
    @MockitoBean FiscalProfileService restaurants;
    @MockitoBean TenantLocationService tenantLocationService;
    @MockitoBean es.brasatech.fastbite.application.tenant.LegalAcceptancePort acceptances;
    @MockitoBean es.brasatech.fastbite.application.settings.GuestPrivacyNoticeService privacyNotices;

    @BeforeEach
    void setUp() {
        when(privacyNotices.findActive()).thenReturn(Optional.empty());
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        when(tenantLocationService.getLocation("kebab")).thenReturn(Optional.of(
                new TenantLocation("1", "ana", "kebab", "RESTAURANT", null)));
        when(restaurants.get()).thenReturn(new FiscalProfile("Ana García López", "Kebab House", "12345678Z",
                "Calle Mayor 12", "28013", "Madrid", "Madrid", "+34 600 000 000", "hola@kebabhouse.es", "KH",
                new BigDecimal("10")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"notice", "privacy", "cookies", "terms", "data-processing"})
    void everyPageExistsInEveryLanguage(String page) throws Exception {
        for (String lang : new String[]{"es", "en", "pt"}) {
            mockMvc.perform(get("/legal/" + page).param("lang", lang))
                    .andExpect(status().isOk())
                    .andExpect(content().string(allOf(containsString("<html lang=\"" + lang + "\""),
                            containsString("<h1>"), containsString("/legal/terms?lang=" + lang))));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"notice", "privacy", "terms", "data-processing"})
    void theContractualPagesNameTheCompany(String page) throws Exception {
        for (String lang : new String[]{"es", "en", "pt"}) {
            mockMvc.perform(get("/legal/" + page).param("lang", lang))
                    .andExpect(content().string(allOf(containsString("BRASATECH DIGITAL SOLUTIONS, S.L.U."),
                            containsString("B09767898"))));
        }
    }

    @Test
    void theLegalNoticeHasTheRequiredDetails() throws Exception {
        mockMvc.perform(get("/legal/notice").param("lang", "es"))
                .andExpect(content().string(allOf(containsString("B09767898"), containsString("Calle Ganaderos, 24, bajo"),
                        containsString("info@brasatech.es"), containsString("tomo 4454, folio 21"),
                        not(containsString("[Pendiente")))));
    }

    @Test
    void theTermsShowThePricesTheAppCharges() throws Exception {
        mockMvc.perform(get("/legal/terms").param("lang", "en"))
                .andExpect(content().string(allOf(containsString("€<span>49</span> per month per location"),
                        containsString("<span>30</span>-day free trial"), containsString("<span>2,5</span>%"))));
    }

    @Test
    void theCookiePolicyNamesTheSessionCookieAsConfigured() throws Exception {
        mockMvc.perform(get("/legal/cookies").param("lang", "es"))
                .andExpect(content().string(allOf(containsString("<code>FASTBITE_SANDBOX_SESSION</code>"),
                        containsString("fastbite.allergens"))));
    }

    @Test
    void thePrivacyPolicyOnlySendsOffersToThoseWhoOptedIn() throws Exception {
        mockMvc.perform(get("/legal/privacy").param("lang", "es"))
                .andExpect(content().string(allOf(containsString("art. 21.1 LSSI-CE"), not(containsString("21.2")),
                        containsString("Puede retirar su consentimiento"), containsString("privacidad@brasatech.es"),
                        containsString("Nunca enviamos ofertas a los clientes de los restaurantes"),
                        containsString("Registros técnicos de seguridad: 30 días"), containsString("tomo 4454"),
                        not(containsString("90 días")))));
    }

    @Test
    void theTermsAreForBusinessesAndTheSelfEmployedAndEndAfterThirtyUnpaidDays() throws Exception {
        mockMvc.perform(get("/legal/terms").param("lang", "es"))
                .andExpect(content().string(allOf(containsString("incluidos los autónomos"),
                        containsString("No pueden contratarlo quienes actúen como consumidores"),
                        containsString("la fecha y la versión aceptada"),
                        containsString("el contrato de ese local termina"),
                        containsString("Exige tener activados los pagos en línea"),
                        containsString("Registro de apoderamientos"))));
    }

    @Test
    void theLanguageFollowsTheBrowserUnlessOneIsChosen() throws Exception {
        mockMvc.perform(get("/legal/privacy").locale(Locale.forLanguageTag("pt")))
                .andExpect(content().string(containsString("Política de privacidade")));
        mockMvc.perform(get("/legal/privacy").locale(Locale.GERMAN))
                .andExpect(content().string(containsString("Política de privacidad")));
        mockMvc.perform(get("/legal/privacy").locale(Locale.forLanguageTag("pt")).param("lang", "en"))
                .andExpect(content().string(containsString("Privacy policy")));
    }

    @Test
    void theTabAndTheNavigationAreTitledInTheChosenLanguage() throws Exception {
        mockMvc.perform(get("/legal/terms").param("lang", "es"))
                .andExpect(content().string(containsString("<title>Condiciones del servicio · FastBite</title>")))
                .andExpect(content().string(containsString(">Aviso legal</a>")))
                .andExpect(content().string(containsString(">Política de cookies</a>")))
                .andExpect(content().string(containsString(">Contrato de encargo del tratamiento</a>")))
                .andExpect(content().string(not(containsString(">null<"))));
        mockMvc.perform(get("/legal/notice").param("lang", "en"))
                .andExpect(content().string(allOf(containsString("<title>Legal notice · FastBite</title>"),
                        containsString(">Terms of service</a>"))));
        mockMvc.perform(get("/kebab/privacy").param("lang", "pt"))
                .andExpect(content().string(containsString("<title>Aviso de privacidade para clientes · FastBite</title>")));
    }

    @Test
    void anUnknownPageIsNotFound() throws Exception {
        mockMvc.perform(get("/legal/whatever")).andExpect(status().isNotFound());
    }

    @Test
    void eachRestaurantTellsItsGuestsWhoDecidesAboutTheirData() throws Exception {
        mockMvc.perform(get("/kebab/privacy").param("lang", "es"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Kebab House"), containsString("Ana García López"),
                        containsString("12345678Z"), containsString("hola@kebabhouse.es"),
                        containsString("/kebab/privacy?lang=en"), not(containsString("aún no ha completado")))));
    }

    @Test
    void aRestaurantWithoutBusinessDetailsSaysSo() throws Exception {
        when(restaurants.get()).thenReturn(FiscalProfile.EMPTY);

        mockMvc.perform(get("/kebab/privacy").param("lang", "es"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("[El restaurante aún no ha completado sus datos]")));
    }

    @Test
    void thePrivacyNoticeIsOnlyForARestaurant() throws Exception {
        mockMvc.perform(get("/privacy")).andExpect(status().isNotFound());
    }

    @Test
    void legalAndPrivacyCannotBeRestaurantAddresses() {
        assertThatThrownBy(() -> TenantRegistrationRules.tenantId("legal")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantRegistrationRules.tenantId("privacy")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void badOrMissingUnsubscribeTokenReturns400AndTouchesNoDatabase() throws Exception {
        mockMvc.perform(get("/legal/unsubscribe"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("privacidad@brasatech.es")));

        mockMvc.perform(get("/legal/unsubscribe").param("token", "forged.token"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("privacidad@brasatech.es")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/legal/unsubscribe")
                        .param("token", "forged.token"))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verifyNoInteractions(acceptances);
    }

    @Test
    void validUnsubscribeTokenShowsConfirmationOnGetAndWithdrawsOnlyOnPostIncludingRfc8058OneClick() throws Exception {
        String token = unsubscribeLinks.issueToken("optinowner").orElseThrow();

        // GET shows confirmation button and does NOT withdraw (protecting against mail scanner prefetch)
        mockMvc.perform(get("/legal/unsubscribe").param("token", token).param("lang", "es"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Retirar consentimiento")));
        org.mockito.Mockito.verifyNoInteractions(acceptances);

        // Browser form POST withdraws consent
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/legal/unsubscribe")
                        .param("token", token)
                        .param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Consent withdrawn")));
        org.mockito.Mockito.verify(acceptances).withdraw(
                org.mockito.ArgumentMatchers.eq("optinowner"),
                org.mockito.ArgumentMatchers.eq(es.brasatech.fastbite.application.tenant.LegalAcceptancePort.MARKETING),
                org.mockito.ArgumentMatchers.any());

        // RFC 8058 One-Click POST (List-Unsubscribe=One-Click without CSRF cookie) also withdraws
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/legal/unsubscribe")
                        .queryParam("token", token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                        .content("List-Unsubscribe=One-Click"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(acceptances, org.mockito.Mockito.times(2)).withdraw(
                org.mockito.ArgumentMatchers.eq("optinowner"),
                org.mockito.ArgumentMatchers.eq(es.brasatech.fastbite.application.tenant.LegalAcceptancePort.MARKETING),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void validTokenForUnknownUsernameReturns200WithSameConfirmationPage() throws Exception {
        String unknownToken = unsubscribeLinks.issueToken("unknown-account").orElseThrow();

        mockMvc.perform(get("/legal/unsubscribe").param("token", unknownToken).param("lang", "es"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Retirar consentimiento")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/legal/unsubscribe")
                        .param("token", unknownToken)
                        .param("lang", "es"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Consentimiento retirado")));
    }

    @Test
    void activeCustomPrivacyNoticeRendersWrapperPageAndStreamsPdfBytes() throws Exception {
        byte[] pdfBytes = "%PDF-1.4\nCustom privacy notice\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var notice = new es.brasatech.fastbite.domain.settings.GuestPrivacyNotice(
                2, "kebab-privacy.pdf", "privacy/privacy-v2-100.pdf",
                pdfBytes.length, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "ana", java.time.Instant.parse("2026-10-27T12:00:00Z"), true);
        when(privacyNotices.findActive()).thenReturn(Optional.of(notice));
        when(privacyNotices.readActiveBytes()).thenReturn(Optional.of(pdfBytes));

        // Wrapper page in English puts Open PDF & Download links first, responsibility line, and wide-screen iframe
        mockMvc.perform(get("/kebab/privacy").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Kebab House is the data controller and is responsible for this privacy notice"),
                        containsString("Open the privacy notice (PDF)"),
                        containsString("/kebab/privacy/pdf?download=true"),
                        containsString("<iframe class=\"privacy-pdf-frame\" src=\"/kebab/privacy/pdf\""))));

        // Direct PDF endpoint streams inline PDF with nosniff and no-store
        mockMvc.perform(get("/kebab/privacy/pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(org.springframework.http.MediaType.APPLICATION_PDF))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", "inline; filename=\"privacy-kebab-v2.pdf\""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Content-Type-Options", "nosniff"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store"))
                .andExpect(content().bytes(pdfBytes));

        // Download parameter switches Content-Disposition to attachment
        mockMvc.perform(get("/kebab/privacy/pdf").param("download", "true"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", "attachment; filename=\"privacy-kebab-v2.pdf\""));
    }

    @Test
    void privacyPdfEndpointReturns404WhenUsingGeneratedNotice() throws Exception {
        when(privacyNotices.findActive()).thenReturn(Optional.empty());

        mockMvc.perform(get("/kebab/privacy/pdf"))
                .andExpect(status().isNotFound());
    }
}
