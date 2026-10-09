package es.brasatech.fastbite.menu;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.controller.MenuController;
import es.brasatech.fastbite.domain.order.CartItem;
import es.brasatech.fastbite.dto.menu.MenuData;
import es.brasatech.fastbite.dto.office.MenuDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.*;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WithMockUser
@WebMvcTest(controllers = MenuController.class, properties = {"spring.messages.basename=i18n/messages"})
@DisplayName("MenuController Tests")
@ContextConfiguration(classes = {TestConfig.class, MenuController.class, es.brasatech.fastbite.security.SecurityConfig.class})
class MenuControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @MockitoBean
        private MenuDataService menuDataService;

        @MockitoBean
        private es.brasatech.fastbite.application.table.TableService tableService;

        @MockitoBean
        private es.brasatech.fastbite.application.table.TableSignatureUtil tableSignatureUtil;

        @MockitoBean
        private es.brasatech.fastbite.application.discount.DiscountService discountService;

        @MockitoBean
        private es.brasatech.fastbite.application.tenant.TenantLocationService tenantLocationService;

        @MockitoBean
        private es.brasatech.fastbite.application.order.OrderPricingService orderPricingService;

        @MockitoBean
        private es.brasatech.fastbite.application.order.OrderService orderService;

        @MockitoBean
        private es.brasatech.fastbite.application.settings.RestaurantSettingsService settingsService;

        @MockitoBean
        private es.brasatech.fastbite.service.OrderCheckoutService orderCheckoutService;

        private List<CartItem> testCartItems;
        private MockHttpSession mockSession;

        @BeforeEach
        void setUp() {

                // Mock MenuDataService to return MenuData with required dictionary entries
                Map<String, String> dictionary = new HashMap<>();
                dictionary.put("cartEmpty", "Your cart is empty");
                dictionary.put("confirmAndSubmitBtn", "Confirm and Submit Order");
                dictionary.put("total", "Total");
                dictionary.put("subtotal", "Subtotal");
                dictionary.put("tax", "Tax");
                dictionary.put("orderConfirmation", "Order Confirmation");
                dictionary.put("orderTotal", "Order Total");
                dictionary.put("orderItems", "Order Items");

                when(menuDataService.buildMenuData(any(Locale.class)))
                                .thenReturn(new MenuData(new HashMap<>(), List.of(), "", dictionary, List.of(), ""));

                // Mock TableSignatureUtil to return true for standard signatures
                org.mockito.Mockito.when(tableSignatureUtil.isValid(any(), any(), any())).thenReturn(true);
                org.mockito.Mockito.when(tableSignatureUtil.generateSignature(any(), any())).thenReturn("val_sig");
                when(settingsService.get()).thenReturn(es.brasatech.fastbite.domain.settings.RestaurantSettings.DEFAULTS);
                // Pricing has its own tests; here the catalog agrees with the request
                org.mockito.Mockito.when(orderPricingService.price(any(), any())).thenAnswer(call -> call.getArgument(0));
                org.mockito.Mockito.when(discountService.calculateDiscount(any(), any(), any())).thenReturn(BigDecimal.ZERO);
                org.mockito.Mockito.when(discountService.calculateDiscount(any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
                org.mockito.Mockito.when(discountService.calculateCartBreakdown(any(), any(), any()))
                                .thenCallRealMethod();

                // Create test cart items
                testCartItems = new ArrayList<>();
                testCartItems.add(new CartItem(
                                "1",
                                "item-1",
                                "Burger",
                                "Delicious burger",
                                "burger.jpg",
                                2,
                                new ArrayList<>(),
                                new BigDecimal("10.00")));
                testCartItems.add(new CartItem(
                                "2",
                                "item-2",
                                "Fries",
                                "Crispy fries",
                                "fries.jpg",
                                1,
                                new ArrayList<>(),
                                new BigDecimal("5.00")));

                // Setup mock session
                mockSession = new MockHttpSession();
                mockSession.setAttribute("orderNumber", "ORD-12345");
                mockSession.setAttribute("cart", testCartItems);
        }

        @Test
        @DisplayName("GET /menu - Should return menu page")
        void testGetMenu() throws Exception {
                mockMvc.perform(get("/default/menu").with(csrf()))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(view().name("fastfood/menu"))
                                .andExpect(model().attributeExists("menuData"));
        }

        @Test
        @DisplayName("GET /menu with table param and missing token - Should fail token validation")
        void testGetMenuWithTableMissingToken() throws Exception {
                when(tableService.validateAndBindTableSession(eq("table-1"), eq(null), any(), any()))
                                .thenThrow(new IllegalArgumentException("Invalid or missing secure table token. Scan the QR code at your table."));

                mockMvc.perform(get("/default/menu").param("table", "table-1").with(csrf()))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(view().name("fastfood/menu"))
                                .andExpect(model().attribute("errorMessage", "Invalid or missing secure table token. Scan the QR code at your table."));
        }

        @Test
        @DisplayName("GET /menu with table and valid token - Should set session attributes")
        void testGetMenuWithTableAndToken() throws Exception {
                es.brasatech.fastbite.domain.table.Table table = new es.brasatech.fastbite.domain.table.Table("table-1", "Table 1", 4, es.brasatech.fastbite.domain.table.TableStatus.AVAILABLE, true, new ArrayList<>());
                when(tableService.findById("table-1")).thenReturn(Optional.of(table));
                when(tableService.validateAndBindTableSession(eq("table-1"), eq("valid-token"), any(), any()))
                                .thenReturn("table-1");

                mockMvc.perform(get("/default/menu").param("table", "table-1").param("token", "valid-token").with(csrf()))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(view().name("fastfood/menu"))
                                .andExpect(result -> {
                                        MockHttpSession session = (MockHttpSession) result.getRequest().getSession();
                                        assert "table-1".equals(session.getAttribute("tableNumber"));
                                        assert "Table 1".equals(session.getAttribute("tableName"));
                                });
        }

        @Test
        @DisplayName("POST /api/calculate-cart - Should calculate cart totals and return cart fragment")
        void testCalculateCart() throws Exception {
                String cartJson = objectMapper.writeValueAsString(testCartItems);

                mockMvc.perform(post("/api/calculate-cart")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cartJson))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(view().name("fastfood/fragments/menu :: #cart"))
                                .andExpect(model().attributeExists("cart"))
                                .andExpect(model().attributeExists("subtotal"))
                                .andExpect(model().attributeExists("tax"))
                                .andExpect(model().attributeExists("total"))
                                .andExpect(model().attribute("cart", hasSize(2)))
                                .andExpect(model().attribute("subtotal", new BigDecimal("25.00")))
                                .andExpect(model().attribute("tax", new BigDecimal("2.27")))
                                .andExpect(model().attribute("total", new BigDecimal("25.00")));
        }

        @Test
        @DisplayName("POST /{tenantId}/api/calculate-cart - Path-prefixed menu pages reach the cart")
        void testCalculateCartWithTenantPrefix() throws Exception {
                mockMvc.perform(post("/kebab/api/calculate-cart")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(testCartItems)))
                                .andExpect(status().isOk())
                                .andExpect(view().name("fastfood/fragments/menu :: #cart"));
        }

        @Test
        @DisplayName("POST /{tenantId}/api/toast - Guests get the popup without rebuilding the menu")
        void testToastIsPublicAndCheap() throws Exception {
                mockMvc.perform(post("/kebab/api/toast")
                                .with(anonymous())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"Added\"}"))
                                .andExpect(status().isOk())
                                .andExpect(view().name("fastfood/fragments/menu :: toast"));

                verify(menuDataService, never()).buildMenuData(any());
        }

        @Test
        @DisplayName("POST /api/calculate-cart - Should handle empty cart")
        void testCalculateCartEmpty() throws Exception {
                String emptyCartJson = objectMapper.writeValueAsString(new ArrayList<>());

                mockMvc.perform(post("/api/calculate-cart")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(emptyCartJson))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(view().name("fastfood/fragments/menu :: #cart"))
                                .andExpect(model().attributeExists("cart"))
                                .andExpect(model().attribute("cart", hasSize(0)))
                                .andExpect(model().attribute("subtotal", new BigDecimal("0")))
                                .andExpect(model().attribute("tax", new BigDecimal("0.00")))
                                .andExpect(model().attribute("total", new BigDecimal("0")));
        }

        @Test
        @DisplayName("POST /api/calculate-confirmation - Should calculate confirmation totals and return confirmation fragment")
        void testCalculateConfirmation() throws Exception {
                String cartJson = objectMapper.writeValueAsString(testCartItems);

                mockMvc.perform(post("/api/calculate-confirmation")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cartJson))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(view().name("fastfood/fragments/menu :: #confirmation"))
                                .andExpect(model().attributeExists("cart"))
                                .andExpect(model().attributeExists("subtotal"))
                                .andExpect(model().attributeExists("tax"))
                                .andExpect(model().attributeExists("total"))
                                .andExpect(model().attribute("cart", hasSize(2)))
                                .andExpect(model().attribute("subtotal", new BigDecimal("25.00")))
                                .andExpect(model().attribute("tax", new BigDecimal("2.27")))
                                .andExpect(model().attribute("total", new BigDecimal("25.00")));
        }

        @Test
        @DisplayName("POST /api/calculate-confirmation - Should handle single item")
        void testCalculateConfirmationSingleItem() throws Exception {
                List<CartItem> singleItem = List.of(new CartItem(
                                "1",
                                "item-1",
                                "Pizza",
                                "Delicious pizza",
                                "pizza.jpg",
                                1,
                                new ArrayList<>(),
                                new BigDecimal("15.00")));
                String cartJson = objectMapper.writeValueAsString(singleItem);

                mockMvc.perform(post("/api/calculate-confirmation")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cartJson))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(view().name("fastfood/fragments/menu :: #confirmation"))
                                .andExpect(model().attribute("cart", hasSize(1)))
                                .andExpect(model().attribute("subtotal", new BigDecimal("15.00")))
                                .andExpect(model().attribute("tax", new BigDecimal("1.36")))
                                .andExpect(model().attribute("total", new BigDecimal("15.00")));
        }

        @Test
        @DisplayName("GET /select-payment - Shows the saved order of this guest, not what the browser holds")
        void testSelectPaymentShowsTheSavedOrder() throws Exception {
                var saved = new es.brasatech.fastbite.domain.order.Order(testCartItems, 12,
                                es.brasatech.fastbite.domain.order.OrderPaymentStatus.UNPAID,
                                es.brasatech.fastbite.domain.order.OrderChannel.TABLE, "en");
                when(orderService.findById("order-12")).thenReturn(java.util.Optional.of(saved));
                mockSession.setAttribute("orderId", "order-12");

                mockMvc.perform(get("/default/select-payment").session(mockSession).with(csrf()))
                                .andExpect(status().isOk())
                                .andExpect(view().name("fastfood/paymentSelection"))
                                .andExpect(model().attribute("order", saved))
                                .andExpect(model().attribute("subtotal", new BigDecimal("25.00")))
                                .andExpect(content().string(org.hamcrest.Matchers.containsString("2 x Burger")))
                                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-order-total=\"25.00\"")));
        }

        @Test
        @DisplayName("GET /select-payment - Without an order there is nothing to pay")
        void testSelectPaymentWithoutAnOrderGoesBackToTheMenu() throws Exception {
                mockMvc.perform(get("/default/select-payment").session(new MockHttpSession()).with(csrf()))
                                .andExpect(status().isFound())
                                .andExpect(redirectedUrl("/menu"));
        }

        @Test
        void cancelledOrdersShowAnExplanationInsteadOfPaymentControls() throws Exception {
                var cancelled = new es.brasatech.fastbite.domain.order.Order(testCartItems, 12,
                                es.brasatech.fastbite.domain.order.OrderPaymentStatus.UNPAID,
                                es.brasatech.fastbite.domain.order.OrderChannel.ONLINE, "en")
                                .cancel(es.brasatech.fastbite.domain.order.Order.PAYMENT_EXPIRED_REASON);
                when(orderService.findById("expired")).thenReturn(java.util.Optional.of(cancelled));
                mockSession.setAttribute("orderId", "expired");
                mockMvc.perform(get("/default/select-payment").session(mockSession).with(csrf()))
                                .andExpect(status().isOk())
                                .andExpect(view().name("fastfood/orderCancelled"))
                                .andExpect(content().string(org.hamcrest.Matchers.containsString("Order cancelled")))
                                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"paymentForm\""))));
        }

        @Test
        @DisplayName("POST /api/calculate-cart - Shows the VAT included in the price, at the line's own rate")
        void testTaxCalculation() throws Exception {
                List<CartItem> itemsForTaxTest = List.of(new CartItem(
                                "1",
                                "item-1",
                                "Test Item",
                                "Description",
                                "image.jpg",
                                1,
                                new ArrayList<>(),
                                new BigDecimal("100.00"),
                                new BigDecimal("21.00")));
                String cartJson = objectMapper.writeValueAsString(itemsForTaxTest);

                mockMvc.perform(post("/api/calculate-cart")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cartJson))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(model().attribute("subtotal", new BigDecimal("100.00")))
                                .andExpect(model().attribute("tax", new BigDecimal("17.36")))
                                .andExpect(model().attribute("total", new BigDecimal("100.00")));
        }

        @Test
        @DisplayName("POST /api/calculate-cart - Should handle multiple quantities correctly")
        void testMultipleQuantities() throws Exception {
                List<CartItem> multiQuantityItems = List.of(new CartItem(
                                "1",
                                "item-1",
                                "Burger",
                                "Description",
                                "burger.jpg",
                                5,
                                new ArrayList<>(),
                                new BigDecimal("10.00")));
                String cartJson = objectMapper.writeValueAsString(multiQuantityItems);

                mockMvc.perform(post("/api/calculate-cart")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cartJson))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(model().attribute("subtotal", new BigDecimal("50.00")))
                                .andExpect(model().attribute("tax", new BigDecimal("4.55")))
                                .andExpect(model().attribute("total", new BigDecimal("50.00")));
        }
}
