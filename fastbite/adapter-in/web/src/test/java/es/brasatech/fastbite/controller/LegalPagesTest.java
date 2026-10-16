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
        TenantRoutingResolver.class, WebConfig.class})
class LegalPagesTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean FiscalProfileService restaurants;
    @MockitoBean TenantLocationService tenantLocationService;

    @BeforeEach
    void setUp() {
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
                    .andExpect(content().string(allOf(containsString("BRASATECH DIGITAL SOLUTIONS S.L."),
                            containsString("B09767898"))));
        }
    }

    @Test
    void theLegalNoticeHasTheRequiredDetailsAndSaysWhatIsStillMissing() throws Exception {
        mockMvc.perform(get("/legal/notice").param("lang", "es"))
                .andExpect(content().string(allOf(containsString("B09767898"), containsString("Calle Ganaderos, 24"),
                        containsString("info@brasatech.es"), containsString("[Pendiente: Registro Mercantil"))));
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
}
