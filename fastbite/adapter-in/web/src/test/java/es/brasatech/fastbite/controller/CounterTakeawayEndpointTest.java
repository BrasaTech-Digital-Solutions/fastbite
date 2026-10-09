package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.discount.DiscountService;
import es.brasatech.fastbite.application.office.CustomizationService;
import es.brasatech.fastbite.application.office.GroupService;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.application.order.OrderNumberService;
import es.brasatech.fastbite.application.order.OrderPricingService;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.payment.PaymentService;
import es.brasatech.fastbite.application.settings.RestaurantSettingsService;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.order.CartItem;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.ServiceType;
import es.brasatech.fastbite.domain.product.ProductDto;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.security.TenantUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CounterController.class)
@ContextConfiguration(classes = {TestConfig.class, CounterController.class, OrderPricingService.class,
        BadRequestAdvice.class, SecurityConfig.class, TenantRoutingResolver.class, WebConfig.class})
class CounterTakeawayEndpointTest {

    private static final String KEBAB_LINE = "{\"id\":\"l1\",\"itemId\":\"kebab\",\"quantity\":2,\"price\":0.01,\"customizations\":[]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private es.brasatech.fastbite.application.fiscal.FiscalProfileService fiscalProfileService;

    @MockitoBean
    private OrderService orderService;
    @MockitoBean
    private OrderNumberService orderNumberService;
    @MockitoBean
    private RestaurantSettingsService settingsService;
    @MockitoBean
    private ProductService productService;
    @MockitoBean
    private CustomizationService customizationService;
    @MockitoBean
    private TableService tableService;
    @MockitoBean
    private PaymentService paymentService;
    @MockitoBean
    private GroupService groupService;
    @MockitoBean
    private DiscountService discountService;
    @MockitoBean
    private TenantLocationService tenantLocationService;

    @BeforeEach
    void setUp() {
        when(fiscalProfileService.defaultVatRate()).thenReturn(new java.math.BigDecimal("10.00"));
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        when(settingsService.get()).thenReturn(RestaurantSettings.DEFAULTS);
        when(productService.findById("kebab")).thenReturn(Optional.of(new ProductDto("kebab", "Kebab",
                new BigDecimal("6.50"), "Beef kebab", "/kebab.webp", Set.of(), true)));
        when(orderNumberService.next()).thenReturn(12);
        when(orderService.createOrder(any(), anyInt(), any(), any(), any(), any(), any(), any())).thenAnswer(call ->
                new Order(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3),
                        call.getArgument(4), call.getArgument(5), call.getArgument(6), call.getArgument(7)));
    }

    /** A cashier of the kebab restaurant, working on its counter page. */
    private static MockHttpServletRequestBuilder asCashier(MockHttpServletRequestBuilder request) {
        var authorities = AuthorityUtils.createAuthorityList("ROLE_CASHIER");
        var user = new TenantUser("carla", "hash", true, authorities, "kebab");
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT",
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities)));
        return request.session(session).with(csrf()).header("Referer", "http://localhost/kebab/counter")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static String order(String tableId, boolean paid, String customerName) {
        return "{\"items\":[" + KEBAB_LINE + "],\"tableId\":" + (tableId == null ? "null" : "\"" + tableId + "\"")
                + ",\"paymentMethod\":\"CASH\",\"paid\":" + paid
                + ",\"customerName\":" + (customerName == null ? "null" : "\"" + customerName + "\"") + "}";
    }

    @Test
    void aTakeawayOrderCanBeLeftToPayAtPickup() throws Exception {
        mockMvc.perform(asCashier(post("/counter/api/order")).content(order(null, false, "Marta")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value(12));

        verify(orderService).createOrder(any(), eq(12), eq(OrderPaymentStatus.UNPAID), eq(OrderChannel.COUNTER), any(),
                eq("carla"), eq("Marta"), eq(ServiceType.TAKEAWAY));
        verify(tableService, never()).assignOrder(any(), any());
    }

    @Test
    void anOrderForATableIsServedAtTheTable() throws Exception {
        mockMvc.perform(asCashier(post("/counter/api/order")).content(order("t1", false, null)))
                .andExpect(status().isOk());

        verify(orderService).createOrder(any(), eq(12), eq(OrderPaymentStatus.UNPAID), eq(OrderChannel.COUNTER), any(),
                eq("carla"), any(), eq(ServiceType.DINE_IN));
        verify(tableService).assignOrder(eq("t1"), any());
    }

    @Test
    void whatTheRestaurantSwitchedOffIsRefusedWithoutTakingAnOrderNumber() throws Exception {
        when(settingsService.get()).thenReturn(new RestaurantSettings(false, false, 0, 0));

        mockMvc.perform(asCashier(post("/counter/api/order")).content(order(null, false, "Marta")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Takeaway orders are switched off for this restaurant"));
        mockMvc.perform(asCashier(post("/counter/api/order")).content(order("t1", true, null)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Table orders are switched off for this restaurant"));

        verify(orderNumberService, never()).next();
        verify(orderService, never()).createOrder(any(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aSalePaidOnTheSpotAlwaysWorks() throws Exception {
        when(settingsService.get()).thenReturn(new RestaurantSettings(false, false, 0, 0));

        mockMvc.perform(asCashier(post("/counter/api/order")).content(order(null, true, null)))
                .andExpect(status().isOk());

        verify(orderService).createOrder(any(), eq(12), eq(OrderPaymentStatus.PAID), eq(OrderChannel.COUNTER), any(),
                eq("carla"), any(), eq(ServiceType.TAKEAWAY));
    }

    @Test
    void payingAnOrderLeavesItsLinesAndPricesAlone() throws Exception {
        mockMvc.perform(asCashier(post("/counter/api/orders/o1/pay")))
                .andExpect(status().isOk());

        verify(orderService).markOrderPaid("o1");
        verify(orderService, never()).update(any(), any());
        verify(productService, never()).findById(any());
    }

    @Test
    void editingATakeawayOrderRepricesItAndKeepsItATakeawayOrder() throws Exception {
        Order existing = new Order(List.of(), 9, OrderPaymentStatus.UNPAID, OrderChannel.COUNTER, "en", "carla",
                "Marta", ServiceType.TAKEAWAY);
        when(orderService.findById("o1")).thenReturn(Optional.of(existing));

        mockMvc.perform(asCashier(put("/counter/api/orders/o1")).content(order(null, false, null)))
                .andExpect(status().isOk());

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderService).update(eq("o1"), saved.capture());
        assertThat(saved.getValue().serviceType()).isEqualTo(ServiceType.TAKEAWAY);
        assertThat(saved.getValue().customerName()).isEqualTo("Marta");
        assertThat(saved.getValue().paymentStatus()).isEqualTo(OrderPaymentStatus.UNPAID);
        assertThat(saved.getValue().items()).singleElement().extracting(CartItem::totalPrice)
                .isEqualTo(new BigDecimal("13.00"));
    }

    @Test
    void orderCartFragmentCalculatesPricesWithValidCustomizations() throws Exception {
        var opt = new es.brasatech.fastbite.domain.customization.CustomizationOptionDto("opt-cheese", "Extra Cheese", new BigDecimal("1.50"));
        var cust = new es.brasatech.fastbite.domain.customization.CustomizationDto("c-cheese", "Cheese", "checkbox", List.of(opt), 1);
        when(productService.findById("kebab")).thenReturn(Optional.of(new ProductDto("kebab", "Kebab",
                new BigDecimal("6.50"), "Beef kebab", "/kebab.webp", Set.of("c-cheese"), true)));
        when(customizationService.findById("c-cheese")).thenReturn(Optional.of(cust));
        when(discountService.calculateDiscount(any(), any(), any(), eq(OrderChannel.COUNTER))).thenReturn(BigDecimal.ZERO);

        String cartJson = """
                [
                    {
                        "id": "item-1",
                        "productId": "kebab",
                        "itemId": "kebab",
                        "name": "Kebab",
                        "price": 6.50,
                        "quantity": 1,
                        "customizations": [
                            {
                                "id": "opt-cheese",
                                "name": "Extra Cheese",
                                "price": 1.50,
                                "quantity": 1
                            }
                        ]
                    }
                ]
                """;

        mockMvc.perform(asCashier(post("/counter/fragments/order-cart")).content(cartJson))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Cart-Subtotal", "8.00"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Cart-Total", "8.00"));
    }
}
