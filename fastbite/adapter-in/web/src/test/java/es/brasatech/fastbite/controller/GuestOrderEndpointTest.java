package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.office.CustomizationService;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.application.order.OrderNumberService;
import es.brasatech.fastbite.application.order.OrderPricingService;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.settings.RestaurantSettingsService;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.domain.order.CartItem;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.ServiceType;
import es.brasatech.fastbite.domain.product.ProductDto;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
import es.brasatech.fastbite.domain.table.Table;
import es.brasatech.fastbite.domain.table.TableStatus;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.service.OrderCheckoutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OrderController.class)
@ContextConfiguration(classes = {TestConfig.class, OrderController.class, OrderPricingService.class, SecurityConfig.class})
class GuestOrderEndpointTest {

    /** What a guest's browser could send: its own name and price for the product. */
    private static String tamperedCart(String table, int quantity) {
        return """
                {"items":[{"id":"l1","itemId":"kebab","name":"<img src=x onerror=alert(1)>","quantity":%d,"price":0.01,"customizations":[]}],
                 "customerName":"Ana","tableNumber":"%s"}""".formatted(quantity, table);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;
    @MockitoBean
    private OrderNumberService orderNumberService;
    @MockitoBean
    private ProductService productService;
    @MockitoBean
    private CustomizationService customizationService;
    @MockitoBean
    private TableService tableService;
    @MockitoBean
    private RestaurantSettingsService settingsService;
    @MockitoBean
    private OrderCheckoutService orderCheckoutService;
    @MockitoBean
    private TenantLocationService tenantLocationService;
    @MockitoBean
    private es.brasatech.fastbite.service.OrderLiveEventService orderLiveEventService;

    @BeforeEach
    void setUp() {
        when(settingsService.get()).thenReturn(RestaurantSettings.DEFAULTS);
        when(productService.findById("kebab")).thenReturn(Optional.of(new ProductDto("kebab", "Kebab",
                new BigDecimal("6.50"), "Beef kebab", "/kebab.webp", Set.of(), true)));
        when(tableService.findTableByNameOrId("Table 1")).thenReturn(Optional.of(table("t1", "Table 1")));
        when(tableService.findTableByNameOrId("t2")).thenReturn(Optional.of(table("t2", "Table 2")));
        when(tableService.findTableByNameOrId("Table 9")).thenReturn(Optional.empty());
        when(orderNumberService.next()).thenReturn(12);
        when(orderService.createOrderForTable(any(), anyInt(), any(), any(), any())).thenAnswer(call ->
                new Order(call.getArgument(0), call.getArgument(1), OrderPaymentStatus.UNPAID, OrderChannel.TABLE, "en"));
    }

    private static Table table(String id, String name) {
        return new Table(id, name, 4, TableStatus.AVAILABLE, true, List.of());
    }

    @SuppressWarnings("unchecked")
    private List<CartItem> savedItems(String tableId) {
        ArgumentCaptor<List<CartItem>> items = ArgumentCaptor.forClass(List.class);
        verify(orderService).createOrderForTable(items.capture(), eq(12), eq(tableId), any(), eq("Ana"));
        return items.getValue();
    }

    /** A guest whose session the QR code of a table bound to it. */
    private static MockHttpSession atTable(String tableId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("tableNumber", tableId);
        return session;
    }

    @Test
    void theOrderIsSavedWithCatalogPricesAndNames() throws Exception {
        mockMvc.perform(post("/kebab/api/create-order").session(atTable("t2")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(tamperedCart("Table 1", 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        assertThat(savedItems("t2")).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("Kebab");
            assertThat(item.price()).isEqualByComparingTo("6.50");
            assertThat(item.totalPrice()).isEqualByComparingTo("13.00");
        });
    }

    @Test
    void aTableNumberTypedIntoTheFormDoesNotMakeATableOrder() throws Exception {
        mockMvc.perform(post("/kebab/api/create-order").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(tamperedCart("Table 1", 2)))
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Scan the QR code on your table to order"));

        verify(orderService, never()).createOrderForTable(any(), anyInt(), any(), any(), any());
        verify(orderNumberService, never()).next();
    }

    // ---- takeaway: no table, and paid online before the kitchen sees it

    private static String takeawayCart(String customerName) {
        return """
                {"items":[{"id":"l1","itemId":"kebab","quantity":2,"price":0.01,"customizations":[]}],
                 "customerName":"%s","tableNumber":null,"paymentMethod":"table","serviceType":"TAKEAWAY"}""".formatted(customerName);
    }

    private void takeawayCanBePaidOnline() {
        when(orderCheckoutService.isAvailable()).thenReturn(true);
        when(orderCheckoutService.isAvailableForTenant(any())).thenReturn(true);
        when(orderService.createOrder(any(), anyInt(), any(), any(), any(), any(), any(), any())).thenAnswer(call ->
                new Order(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3),
                        call.getArgument(4), call.getArgument(5), call.getArgument(6), call.getArgument(7)));
    }

    @Test
    void aGuestWithoutATableOrdersTakeawayAndHasToPayFirst() throws Exception {
        takeawayCanBePaidOnline();

        mockMvc.perform(post("/kebab/api/create-order").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(takeawayCart("Marta")))
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.payFirst").value(true));

        verify(orderService).createOrder(any(), eq(12), eq(OrderPaymentStatus.UNPAID), eq(OrderChannel.ONLINE), any(),
                isNull(), eq("Marta"), eq(ServiceType.TAKEAWAY));
        verify(orderService, never()).createOrderForTable(any(), anyInt(), any(), any(), any());
        verify(tableService, never()).findTableByNameOrId(any());
    }

    @Test
    void takeawayIsRefusedWhenItIsOffOrCannotBePaidOnline() throws Exception {
        takeawayCanBePaidOnline();
        when(settingsService.get()).thenReturn(new RestaurantSettings(true, false, 0, 0));
        mockMvc.perform(post("/kebab/api/create-order").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(takeawayCart("Marta")))
                .andExpect(jsonPath("$.status").value("error"));

        when(settingsService.get()).thenReturn(new RestaurantSettings(false, true, 0, 0));
        when(orderCheckoutService.isAvailableForTenant(any())).thenReturn(false);
        mockMvc.perform(post("/kebab/api/create-order").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(takeawayCart("Marta")))
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Online takeaway orders are temporarily unavailable because diner payments are not configured"));

        when(orderCheckoutService.isAvailableForTenant(any())).thenReturn(true);
        mockMvc.perform(post("/kebab/api/create-order").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(takeawayCart(" ")))
                .andExpect(jsonPath("$.status").value("error"));

        verify(orderNumberService, never()).next();
        verify(orderService, never()).createOrder(any(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aGuestAtATableCannotTurnTheirOrderIntoTakeaway() throws Exception {
        takeawayCanBePaidOnline();

        mockMvc.perform(post("/kebab/api/create-order").session(atTable("t2")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(takeawayCart("Ana")))
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.payFirst").value(false));

        savedItems("t2");
        verify(orderService, never()).createOrder(any(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void withTableServiceOffAGuestAtATableOrdersTakeaway() throws Exception {
        when(settingsService.get()).thenReturn(new RestaurantSettings(false, true, 0, 0));
        takeawayCanBePaidOnline();

        mockMvc.perform(post("/kebab/api/create-order").session(atTable("t2")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(takeawayCart("Ana")))
                .andExpect(jsonPath("$.payFirst").value(true));
        verify(orderService, never()).createOrderForTable(any(), anyInt(), any(), any(), any());

        when(orderCheckoutService.isAvailableForTenant(any())).thenReturn(false);
        mockMvc.perform(post("/kebab/api/create-order").session(atTable("t2")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(takeawayCart("Ana")))
                .andExpect(jsonPath("$.message").value("Online takeaway orders are temporarily unavailable because diner payments are not configured"));
    }

    @Test
    void aRejectedOrderTakesNoOrderNumber() throws Exception {
        mockMvc.perform(post("/kebab/api/create-order").session(atTable("Table 9")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(tamperedCart("Table 1", 2)))
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Table does not exist"));
        mockMvc.perform(post("/kebab/api/create-order").session(atTable("t2")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[],\"customerName\":\"Ana\"}"))
                .andExpect(jsonPath("$.status").value("error"));
        mockMvc.perform(post("/kebab/api/create-order").session(atTable("t2")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(tamperedCart("Table 1", -2)))
                .andExpect(jsonPath("$.status").value("error"));

        verify(orderNumberService, never()).next();
        verify(orderService, never()).createOrderForTable(any(), anyInt(), any(), any(), any());
    }
}
