package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.office.CustomizationService;
import es.brasatech.fastbite.application.office.GroupService;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.allergen.Allergen;
import es.brasatech.fastbite.domain.customization.CustomizationDto;
import es.brasatech.fastbite.domain.customization.CustomizationOptionDto;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.group.Group;
import es.brasatech.fastbite.domain.product.ProductDto;
import es.brasatech.fastbite.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AllergenChartController.class, properties = "spring.messages.basename=i18n/messages")
@ContextConfiguration(classes = {TestConfig.class, AllergenChartController.class, SecurityConfig.class,
        TenantRoutingResolver.class, WebConfig.class})
class AllergenChartEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private ProductService productService;
    @MockitoBean private GroupService groupService;
    @MockitoBean private CustomizationService customizationService;
    @MockitoBean private FiscalProfileService fiscalProfileService;
    @MockitoBean private TenantLocationService tenantLocationService;

    private static ProductDto dish(String id, String name, boolean onSale, Set<Allergen> allergens) {
        return new ProductDto(id, name, new BigDecimal("9.00"), "", null, Set.of(), onSale, null, allergens);
    }

    @BeforeEach
    void setUp() {
        es.brasatech.fastbite.config.RegisteredTenants.all(tenantLocationService);
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        when(fiscalProfileService.get()).thenReturn(new FiscalProfile("Ana García", "Kebab House", "12345678Z",
                "Calle Mayor 1", "28013", "Madrid", "Madrid", null, null, "KH", null));
        when(productService.findAll()).thenReturn(List.of(
                dish("pizza", "Pizza Mozzarella", true, Set.of(Allergen.GLUTEN, Allergen.MILK)),
                dish("special", "Chef special", true, null),
                dish("gone", "Old dish", false, Set.of())));
        when(groupService.findAll()).thenReturn(List.of(new Group("food", "Food", "", null, List.of("pizza", "special", "gone"))));
        when(customizationService.findAll()).thenReturn(List.of(new CustomizationDto("toppings", "Toppings", "checkbox",
                List.of(new CustomizationOptionDto("cheese", "Extra cheese", BigDecimal.ONE, false, 0, Set.of(Allergen.MILK)),
                        new CustomizationOptionDto("onion", "Onion", BigDecimal.ZERO, false, 0, null)), 0)));
    }

    @Test
    void guestsSeeEveryDishOnSaleAgainstTheFourteenAllergens() throws Exception {
        mockMvc.perform(get("/kebab/allergens").locale(Locale.ENGLISH))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Kebab House"),
                        containsString("Pizza Mozzarella"),
                        containsString("Molluscs"),
                        containsString("aria-label=\"Milk\""),
                        not(containsString("Old dish")))));
    }

    @Test
    void dishesWithoutInformationTellGuestsToAskTheStaff() throws Exception {
        mockMvc.perform(get("/kebab/allergens").locale(Locale.ENGLISH))
                .andExpect(content().string(allOf(
                        containsString("Chef special"),
                        containsString("Ask our staff"),
                        containsString("1 dishes have no allergen information yet"))));
    }

    @Test
    void optionsThatAddAllergensAreListed() throws Exception {
        mockMvc.perform(get("/kebab/allergens").locale(Locale.forLanguageTag("es")))
                .andExpect(content().string(allOf(
                        containsString("Opciones que añaden alérgenos"),
                        containsString("Extra cheese"),
                        not(containsString("Onion")))));
    }
}
