package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.fiscal.FiscalTicketService;
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
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
import es.brasatech.fastbite.domain.table.Table;
import es.brasatech.fastbite.domain.table.TableStatus;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.security.TenantUser;
import es.brasatech.fastbite.service.OrderLiveEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CounterController.class)
@ContextConfiguration(classes = {TestConfig.class, CounterController.class, OrderPricingService.class,
        BadRequestAdvice.class, SecurityConfig.class, TenantRoutingResolver.class, WebConfig.class})
class TableQrTrustEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FiscalProfileService fiscalProfileService;
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
    private es.brasatech.fastbite.application.discount.DiscountService discountService;
    @MockitoBean
    private FiscalTicketService fiscalTicketService;
    @MockitoBean
    private TenantLocationService tenantLocationService;
    @MockitoBean
    private OrderLiveEventService orderLiveEventService;

    @BeforeEach
    void setUp() {
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        when(settingsService.get()).thenReturn(RestaurantSettings.DEFAULTS);
    }

    private static MockHttpServletRequestBuilder asStaff(String username, String role, String tenant, MockHttpServletRequestBuilder request) {
        var authorities = AuthorityUtils.createAuthorityList(role);
        var user = new TenantUser(username, "hash", true, authorities, tenant);
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT",
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities)));
        return request.session(session).with(csrf()).header("Referer", "http://localhost/" + tenant + "/counter")
                .contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void staffCanSwitchTableQrTrustWithAuditAndLiveUpdate() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Table updated = new Table("t1", "Table 1", 4, TableStatus.AVAILABLE, true, List.of(), true, "carla", now);
        when(tableService.setQrTrustedOverride("t1", true, "carla")).thenReturn(Optional.of(updated));

        mockMvc.perform(asStaff("carla", "ROLE_CASHIER", "kebab",
                        post("/counter/api/tables/t1/qr-trusted").param("trusted", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.tableId").value("t1"))
                .andExpect(jsonPath("$.qrTrusted").value(true))
                .andExpect(jsonPath("$.effectiveQrTrusted").value(true));

        verify(tableService).setQrTrustedOverride("t1", true, "carla");
        verify(orderLiveEventService).broadcastTableUpdated("t1");
    }

    @Test
    void staffCanToggleTableQrTrustWithoutParam() throws Exception {
        Table existing = new Table("t1", "Table 1", 4, TableStatus.AVAILABLE, true, List.of(), null, null, null);
        when(tableService.findById("t1")).thenReturn(Optional.of(existing));

        Table updated = new Table("t1", "Table 1", 4, TableStatus.AVAILABLE, true, List.of(), true, "carla", LocalDateTime.now());
        when(tableService.setQrTrustedOverride("t1", true, "carla")).thenReturn(Optional.of(updated));

        mockMvc.perform(asStaff("carla", "ROLE_CASHIER", "kebab",
                        post("/counter/api/tables/t1/qr-trusted")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.effectiveQrTrusted").value(true));

        verify(tableService).setQrTrustedOverride("t1", true, "carla");
        verify(orderLiveEventService).broadcastTableUpdated("t1");
    }

    @Test
    void anonymousUserIsRefused() throws Exception {
        mockMvc.perform(post("/counter/api/tables/t1/qr-trusted").with(csrf())
                        .header("Referer", "http://localhost/kebab/counter")
                        .param("trusted", "true"))
                .andExpect(status().is3xxRedirection());

        verify(tableService, never()).setQrTrustedOverride(anyString(), anyBoolean(), anyString());
        verify(orderLiveEventService, never()).broadcastTableUpdated(anyString());
    }

    @Test
    void staffOfAnotherTenantIsRefused() throws Exception {
        var authorities = AuthorityUtils.createAuthorityList("ROLE_CASHIER");
        var user = new TenantUser("mario", "hash", true, authorities, "pizza");
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT",
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities)));

        mockMvc.perform(post("/counter/api/tables/t1/qr-trusted").session(session).with(csrf())
                        .header("Referer", "http://localhost/kebab/counter")
                        .param("trusted", "true"))
                .andExpect(status().is3xxRedirection());

        verify(tableService, never()).setQrTrustedOverride(anyString(), anyBoolean(), anyString());
    }

    @Test
    void nonStaffRoleIsForbidden() throws Exception {
        var authorities = AuthorityUtils.createAuthorityList("ROLE_GUEST");
        var user = new TenantUser("guest", "hash", true, authorities, "kebab");
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT",
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities)));

        mockMvc.perform(post("/counter/api/tables/t1/qr-trusted").session(session).with(csrf())
                        .header("Referer", "http://localhost/kebab/counter")
                        .param("trusted", "true"))
                .andExpect(status().isForbidden());

        verify(tableService, never()).setQrTrustedOverride(anyString(), anyBoolean(), anyString());
    }

    @Test
    void requestWithoutCsrfIsForbidden() throws Exception {
        var authorities = AuthorityUtils.createAuthorityList("ROLE_CASHIER");
        var user = new TenantUser("carla", "hash", true, authorities, "kebab");
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT",
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities)));

        mockMvc.perform(post("/counter/api/tables/t1/qr-trusted").session(session)
                        .header("Referer", "http://localhost/kebab/counter")
                        .param("trusted", "true"))
                .andExpect(status().isForbidden());

        verify(tableService, never()).setQrTrustedOverride(anyString(), anyBoolean(), anyString());
    }

    @Test
    void unknownTableReturnsNotFound() throws Exception {
        when(tableService.findById("t999")).thenReturn(Optional.empty());
        when(tableService.setQrTrustedOverride(eq("t999"), any(), anyString())).thenReturn(Optional.empty());

        mockMvc.perform(asStaff("carla", "ROLE_CASHIER", "kebab",
                        post("/counter/api/tables/t999/qr-trusted").param("trusted", "true")))
                .andExpect(status().isNotFound());
    }
}
