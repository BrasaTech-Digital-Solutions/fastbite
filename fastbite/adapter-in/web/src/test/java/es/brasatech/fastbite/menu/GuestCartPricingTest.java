package es.brasatech.fastbite.menu;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.discount.DiscountService;
import es.brasatech.fastbite.application.office.CustomizationService;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.application.order.OrderPricingService;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.controller.BadRequestAdvice;
import es.brasatech.fastbite.controller.MenuController;
import es.brasatech.fastbite.domain.customization.CustomizationDto;
import es.brasatech.fastbite.domain.customization.CustomizationOptionDto;
import es.brasatech.fastbite.domain.product.ProductDto;
import es.brasatech.fastbite.application.settings.RestaurantSettingsService;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
import es.brasatech.fastbite.dto.office.MenuDataService;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.service.OrderCheckoutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The guest's cart and confirmation show what the order will really cost, whatever the browser sent. */
@WebMvcTest(controllers = MenuController.class, properties = "fastbite.tax.percentage=10.0")
@ContextConfiguration(classes = {TestConfig.class, MenuController.class, OrderPricingService.class,
        BadRequestAdvice.class, SecurityConfig.class})
class GuestCartPricingTest {

    private static final String TAMPERED_CART = """
            [{"id":"l1","itemId":"kebab","name":"Hacked","description":"x","image":"/x.png","quantity":2,"price":0.01,
              "customizations":[{"id":"toppings-opt-0","name":"Free","price":-9,"quantity":1}]}]""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;
    @MockitoBean
    private CustomizationService customizationService;
    @MockitoBean
    private DiscountService discountService;
    @MockitoBean
    private MenuDataService menuDataService;
    @MockitoBean
    private TableService tableService;
    @MockitoBean
    private TenantLocationService tenantLocationService;
    @MockitoBean
    private es.brasatech.fastbite.application.order.OrderService orderService;
    @MockitoBean
    private RestaurantSettingsService settingsService;
    @MockitoBean
    private OrderCheckoutService orderCheckoutService;

    @BeforeEach
    void setUp() {
        when(settingsService.get()).thenReturn(RestaurantSettings.DEFAULTS);
        when(orderCheckoutService.isAvailable()).thenReturn(true);
        when(orderCheckoutService.isAvailableForTenant(any())).thenReturn(true);
        when(productService.findById("kebab")).thenReturn(Optional.of(new ProductDto("kebab", "Kebab",
                new BigDecimal("6.50"), "Beef kebab", "/kebab.webp", Set.of("toppings"), true)));
        when(productService.findById("gone")).thenReturn(Optional.empty());
        when(customizationService.findById("toppings")).thenReturn(Optional.of(new CustomizationDto("toppings",
                "Toppings", "checkbox", List.of(new CustomizationOptionDto("toppings-opt-0", "Extra cheese",
                new BigDecimal("0.50"), false, 0)), 0)));
        when(discountService.calculateDiscount(any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(discountService.calculateCartBreakdown(any(), any(), any(), anyDouble())).thenCallRealMethod();
    }

    @Test
    void theCartIsRenderedWithCatalogNamesAndPrices() throws Exception {
        mockMvc.perform(post("/kebab/api/calculate-cart").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(TAMPERED_CART))
                .andExpect(status().isOk())
                .andExpect(model().attribute("subtotal", new BigDecimal("14.00"))) // (6.50 + 0.50) x 2
                .andExpect(content().string(containsString("Kebab")))
                .andExpect(content().string(containsString("Extra cheese")))
                .andExpect(content().string(not(containsString("Hacked"))));
    }

    @Test
    void theConfirmationIsRenderedWithCatalogPricesToo() throws Exception {
        mockMvc.perform(post("/kebab/api/calculate-confirmation").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(TAMPERED_CART))
                .andExpect(status().isOk())
                .andExpect(model().attribute("total", new BigDecimal("14.00")))
                .andExpect(content().string(not(containsString("Hacked"))));
    }

    // ---- how the guest can be served

    private ResultActions checkoutStep(MockHttpSession session) throws Exception {
        return mockMvc.perform(post("/kebab/api/calculate-confirmation").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(TAMPERED_CART))
                .andExpect(status().isOk());
    }

    private static final String TAKEAWAY = "type=\"hidden\" name=\"serviceType\" value=\"TAKEAWAY\"";
    private static final String AT_TABLE = "type=\"hidden\" name=\"serviceType\" value=\"DINE_IN\"";

    @Test
    void aGuestWithoutATableQrCodeOrdersTakeawayAndIsToldHowToOrderToATable() throws Exception {
        checkoutStep(new MockHttpSession())
                .andExpect(content().string(containsString(TAKEAWAY)))
                .andExpect(content().string(not(containsString("id=\"tableNumber\""))))
                .andExpect(content().string(containsString("id=\"takeawayFields\"")))
                .andExpect(content().string(containsString("fa-qrcode")))
                .andExpect(content().string(containsString("data-can-order=\"true\"")));
    }

    @Test
    void aGuestWhoScannedATableOrdersForThatTable() throws Exception {
        MockHttpSession atTable = new MockHttpSession();
        atTable.setAttribute("tableNumber", "t2");
        atTable.setAttribute("tableName", "Table 2");

        checkoutStep(atTable)
                .andExpect(content().string(containsString(AT_TABLE)))
                .andExpect(content().string(containsString("value=\"Table 2\"")))
                .andExpect(content().string(containsString("readonly")))
                .andExpect(content().string(not(containsString("id=\"takeawayFields\""))));
    }

    @Test
    void withoutTakeawayOnlyAGuestWithATableQrCodeCanOrder() throws Exception {
        when(orderCheckoutService.isAvailableForTenant(any())).thenReturn(false);
        checkoutStep(new MockHttpSession())
                .andExpect(content().string(containsString("data-can-order=\"false\"")))
                .andExpect(content().string(not(containsString("id=\"customerName\""))));

        when(orderCheckoutService.isAvailableForTenant(any())).thenReturn(true);
        when(settingsService.get()).thenReturn(new RestaurantSettings(true, false, 0, 0));
        checkoutStep(new MockHttpSession())
                .andExpect(content().string(containsString("data-can-order=\"false\"")));
    }

    @Test
    void withNothingOnOfferTheGuestCannotSubmit() throws Exception {
        when(settingsService.get()).thenReturn(new RestaurantSettings(false, false, 0, 0));

        checkoutStep(new MockHttpSession())
                .andExpect(content().string(containsString("data-can-order=\"false\"")))
                .andExpect(content().string(not(containsString("id=\"customerName\""))))
                .andExpect(content().string(containsString("disabled")));
    }

    @Test
    void aCartWithAProductThatLeftTheMenuIsRejectedWithTheReason() throws Exception {
        mockMvc.perform(post("/kebab/api/calculate-cart").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("[{\"itemId\":\"gone\",\"quantity\":1}]"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("A product in this order is no longer on the menu"));
    }
}
