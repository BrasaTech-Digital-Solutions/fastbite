package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.order.OrderNumberService;
import es.brasatech.fastbite.application.order.OrderPricingService;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.settings.RestaurantSettingsService;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.OrderStatus;
import es.brasatech.fastbite.domain.order.ServiceType;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.service.OrderCheckoutService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OrderController.class)
@ContextConfiguration(classes = {
        TestConfig.class,
        OrderController.class,
        SecurityConfig.class,
        es.brasatech.fastbite.config.TenantRoutingResolver.class,
        es.brasatech.fastbite.config.WebConfig.class
})
class OrderStatusEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;
    @MockitoBean
    private OrderNumberService orderNumberService;
    @MockitoBean
    private OrderPricingService orderPricingService;
    @MockitoBean
    private TableService tableService;
    @MockitoBean
    private RestaurantSettingsService settingsService;
    @MockitoBean
    private OrderCheckoutService orderCheckoutService;
    @MockitoBean
    private TenantLocationService tenantLocationService;

    @org.junit.jupiter.api.BeforeEach
    void restaurantsExist() {
        es.brasatech.fastbite.config.RegisteredTenants.all(tenantLocationService);
    }
    @MockitoBean
    private es.brasatech.fastbite.service.OrderLiveEventService orderLiveEventService;

    @Test
    void guestSeesTheStatusOfTheOrderInTheirSession() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(orderService.findById("order-7")).thenReturn(Optional.of(new Order(List.of(), 7, "order-7", now, now,
                OrderStatus.PROCESSING, BigDecimal.TEN, null, OrderPaymentStatus.UNPAID, OrderChannel.TABLE, "es", null, "Ana", ServiceType.DINE_IN)));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("orderId", "order-7");

        mockMvc.perform(get("/kebab/api/order-status").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void guestWithoutAnOrderGetsNotFound() throws Exception {
        mockMvc.perform(get("/api/order-status"))
                .andExpect(status().isNotFound());
    }

    @Test
    void comingBackFromStripeOnlyTheSessionsOwnCheckoutIsChecked() throws Exception {
        Order order = new Order(List.of(), 8, OrderPaymentStatus.PAID, OrderChannel.ONLINE, "en", null, "Marta",
                ServiceType.TAKEAWAY);
        when(orderService.findById("order-8")).thenReturn(Optional.of(order));
        when(orderCheckoutService.confirmReturn("cs_paid")).thenReturn(true);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("orderId", "order-8");
        session.setAttribute("guestCheckoutOrder", "order-8");
        session.setAttribute("guestCheckoutSession", "cs_paid");

        mockMvc.perform(get("/kebab/order-confirmation").session(session).param("session_id", "cs_paid"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alert mt-3 mb-0 alert-success")))
                .andExpect(content().string(containsString("id=\"guest-ticket\"")));
        mockMvc.perform(get("/kebab/order-confirmation").session(session).param("session_id", "cs_other"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"paymentResult\""))));
        org.mockito.Mockito.verify(orderCheckoutService, org.mockito.Mockito.never()).confirmReturn("cs_other");
    }

    @Test
    void pendingCheckoutDoesNotSayThatTheOrderIsBeingPrepared() throws Exception {
        Order order = new Order(List.of(), 8, OrderPaymentStatus.UNPAID, OrderChannel.ONLINE, "en", null, "Marta",
                ServiceType.TAKEAWAY);
        when(orderService.findById("order-8")).thenReturn(Optional.of(order));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("orderId", "order-8");
        session.setAttribute("guestCheckoutOrder", "order-8");
        session.setAttribute("guestCheckoutSession", "cs_open");
        // Opening checkout is not a payment: only its matching return may show pending.
        mockMvc.perform(get("/kebab/order-confirmation").session(session))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/kebab/select-payment"));
        mockMvc.perform(get("/kebab/order-confirmation").session(session).param("session_id", "cs_other"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/kebab/select-payment"));
        mockMvc.perform(get("/kebab/order-confirmation").session(session).param("session_id", "cs_open"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"order-success-title\" hidden=\"hidden\"")))
                .andExpect(content().string(containsString("id=\"guest-ticket\"")));
    }

    @Test
    void anOnlineTakeawayOrderIsNotConfirmedUntilItIsPaid() throws Exception {
        Order takeaway = new Order(List.of(), 8, OrderPaymentStatus.UNPAID, OrderChannel.ONLINE, "en", null, "Marta",
                ServiceType.TAKEAWAY);
        when(orderService.findById("order-8")).thenReturn(Optional.of(takeaway));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("orderId", "order-8");

        mockMvc.perform(get("/kebab/order-confirmation").session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/kebab/select-payment"));

        when(orderService.findById("order-8")).thenReturn(Optional.of(takeaway.setPaymentStatus(OrderPaymentStatus.PAID)));
        mockMvc.perform(get("/kebab/order-confirmation").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void guestsCannotListTheKitchenOrders() throws Exception {
        mockMvc.perform(post("/api/order").with(csrf()))
                .andExpect(status().isFound()); // sent to login
        mockMvc.perform(post("/kebab/api/order").with(csrf()))
                .andExpect(status().isFound());
    }

    @Test
    void staffCanFetchOrdersWithTenantPrefix() throws Exception {
        when(orderService.getAllOrder()).thenReturn(List.of());
        var authorities = org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_CASHIER");
        var tenantUser = new es.brasatech.fastbite.security.TenantUser("cashier", "pass", true, authorities, "kebab");
        var auth = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(tenantUser, null, authorities);

        mockMvc.perform(post("/kebab/api/order").with(csrf()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }
}
