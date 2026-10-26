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
import es.brasatech.fastbite.domain.table.Table;
import es.brasatech.fastbite.domain.table.TableStatus;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.security.TenantUser;
import es.brasatech.fastbite.service.OrderCheckoutService;
import es.brasatech.fastbite.service.OrderLiveEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OrderController.class)
@ContextConfiguration(classes = {
        TestConfig.class,
        OrderController.class,
        SecurityConfig.class,
        es.brasatech.fastbite.config.TenantRoutingResolver.class,
        es.brasatech.fastbite.config.WebConfig.class
})
class DashboardOrderEndpointTest {

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
    private OrderLiveEventService orderLiveEventService;

    @Test
    void postOrderReturnsDashboardOrdersWithTableInfoInSingleBatchQuery() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Order tableOrder = new Order(List.of(), 101, "order-table", now, now,
                OrderStatus.DELIVERED, new BigDecimal("25.00"), null, OrderPaymentStatus.UNPAID,
                OrderChannel.TABLE, "en", null, "Alice", ServiceType.DINE_IN);
        Order takeawayOrder = new Order(List.of(), 102, "order-takeaway", now, now,
                OrderStatus.DONE, new BigDecimal("15.00"), null, OrderPaymentStatus.UNPAID,
                OrderChannel.COUNTER, "en", null, "Bob", ServiceType.TAKEAWAY);

        when(orderService.getAllOrder()).thenReturn(List.of(tableOrder, takeawayOrder));
        Table table = new Table("tbl-1", "Table 4", 4, TableStatus.OCCUPIED, true, List.of("order-table"));
        when(tableService.findTablesByOrderIds(List.of("order-table", "order-takeaway")))
                .thenReturn(Map.of("order-table", table));

        var authorities = AuthorityUtils.createAuthorityList("ROLE_MANAGER");
        var user = new TenantUser("manager", "pass", true, authorities, "kebab");
        var auth = UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);

        mockMvc.perform(post("/kebab/api/order").with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value("order-table"))
                .andExpect(jsonPath("$[0].orderNumber").value(101))
                .andExpect(jsonPath("$[0].status").value("DELIVERED"))
                .andExpect(jsonPath("$[0].paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$[0].tableId").value("tbl-1"))
                .andExpect(jsonPath("$[0].tableName").value("Table 4"))
                .andExpect(jsonPath("$[1].id").value("order-takeaway"))
                .andExpect(jsonPath("$[1].orderNumber").value(102))
                .andExpect(jsonPath("$[1].status").value("DONE"))
                .andExpect(jsonPath("$[1].paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$[1].tableId").doesNotExist())
                .andExpect(jsonPath("$[1].tableName").doesNotExist());

        verify(tableService).findTablesByOrderIds(List.of("order-table", "order-takeaway"));
    }
}
