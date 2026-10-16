package es.brasatech.fastbite.controller;

import com.stripe.model.checkout.Session;
import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantContextFilter;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.service.OrderCheckoutService;
import es.brasatech.fastbite.service.StripeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = StripeCheckoutController.class)
@ContextConfiguration(classes = {TestConfig.class, StripeCheckoutController.class, SecurityConfig.class,
        TenantRoutingResolver.class, WebConfig.class})
class OrderCheckoutEndpointTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockitoBean
    private OrderCheckoutService orderCheckoutService;
    @MockitoBean
    private StripeService stripeService;
    @MockitoBean
    private OrderService orderService;
    @MockitoBean
    private TenantLocationService tenantLocationService;

    private final Order order = new Order(List.of(), 12, OrderPaymentStatus.UNPAID, OrderChannel.TABLE, "en");

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilters(new ForwardedHeaderFilter(),
                        webApplicationContext.getBean(TenantContextFilter.class))
                .apply(springSecurity())
                .build();
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        when(orderService.findById("order-12")).thenReturn(Optional.of(order));
        Session session = new Session();
        session.setId("cs_test_1");
        session.setUrl("https://checkout.stripe.com/c/pay/cs_test_1");
        when(orderCheckoutService.start(any(), any(), anyInt(), any(), any())).thenReturn(session);
    }

    private static MockHttpSession guestWithOrder() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("orderId", "order-12");
        return session;
    }

    @Test
    void theGuestPaysTheirOwnSavedOrderWhateverTheBrowserSends() throws Exception {
        mockMvc.perform(post("/kebab/api/stripe/create-checkout-session").session(guestWithOrder()).with(csrf())
                        .header("Host", "fastbite.example")
                        .header("X-Forwarded-Proto", "https")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipPercent\":10,\"amount\":\"0.01\",\"orderId\":\"someone-else\",\"tenantId\":\"pizza\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutUrl").value("https://checkout.stripe.com/c/pay/cs_test_1"));

        verify(orderCheckoutService).start("kebab", order, 10,
                "https://fastbite.example/kebab/order-confirmation?session_id={CHECKOUT_SESSION_ID}",
                "https://fastbite.example/kebab/select-payment");
    }

    @Test
    void onARestaurantsOwnHostTheGuestComesBackToThatHost() throws Exception {
        mockMvc.perform(post("/api/stripe/create-checkout-session").session(guestWithOrder()).with(csrf())
                        .header("Host", "kebab.localhost:8080")
                        .header("X-Forwarded-Host", "kebab.localhost:8080")
                        .header("X-Forwarded-Proto", "https")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        verify(orderCheckoutService).start(eq("kebab"), eq(order), eq(0),
                eq("https://kebab.localhost:8080/order-confirmation?session_id={CHECKOUT_SESSION_ID}"),
                eq("https://kebab.localhost:8080/select-payment"));
    }

    @Test
    void forwardedHeadersDetermineSchemeAndHostAndPort() throws Exception {
        mockMvc.perform(post("/kebab/api/stripe/create-checkout-session").session(guestWithOrder()).with(csrf())
                        .header("Host", "fastbite-xyz.a.run.app")
                        .header("X-Forwarded-Host", "fastbite.brasatech.es")
                        .header("X-Forwarded-Proto", "https")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        verify(orderCheckoutService).start(eq("kebab"), eq(order), eq(0),
                eq("https://fastbite.brasatech.es/kebab/order-confirmation?session_id={CHECKOUT_SESSION_ID}"),
                eq("https://fastbite.brasatech.es/kebab/select-payment"));

        mockMvc.perform(post("/kebab/api/stripe/create-checkout-session").session(guestWithOrder()).with(csrf())
                        .header("Host", "fastbite-xyz.a.run.app")
                        .header("X-Forwarded-Host", "fastbite.localhost:8088")
                        .header("X-Forwarded-Proto", "http")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        verify(orderCheckoutService).start(eq("kebab"), eq(order), eq(0),
                eq("http://fastbite.localhost:8088/kebab/order-confirmation?session_id={CHECKOUT_SESSION_ID}"),
                eq("http://fastbite.localhost:8088/kebab/select-payment"));
    }

    @Test
    void withoutAnOrderInTheSessionThereIsNothingToPay() throws Exception {
        mockMvc.perform(post("/kebab/api/stripe/create-checkout-session").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":\"order-12\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("There is no order to pay"));

        verify(orderCheckoutService, never()).start(any(), any(), anyInt(), any(), any());
    }

    @Test
    void refusalsReachTheGuestAsAMessage() throws Exception {
        when(orderCheckoutService.start(any(), any(), anyInt(), any(), any()))
                .thenThrow(new IllegalArgumentException("This order is already paid"));

        mockMvc.perform(post("/kebab/api/stripe/create-checkout-session").session(guestWithOrder()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("This order is already paid"));
    }

    @Test
    void theRequestMustComeFromThePaymentPage() throws Exception {
        mockMvc.perform(post("/kebab/api/stripe/create-checkout-session").session(guestWithOrder())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
}
