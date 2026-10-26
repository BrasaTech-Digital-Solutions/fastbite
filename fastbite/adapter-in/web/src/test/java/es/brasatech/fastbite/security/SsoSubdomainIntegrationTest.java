package es.brasatech.fastbite.security;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.controller.MenuController;
import es.brasatech.fastbite.dto.menu.MenuData;
import es.brasatech.fastbite.dto.office.MenuDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MenuController.class)
@DisplayName("SSO Subdomain Integration Tests")
@ContextConfiguration(classes = {
        TestConfig.class,
        MenuController.class,
        SecurityConfig.class,
        TenantRoutingResolver.class,
        es.brasatech.fastbite.config.WebConfig.class
})
class SsoSubdomainIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MenuDataService menuDataService;

    @MockitoBean
    private es.brasatech.fastbite.application.table.TableService tableService;

    @MockitoBean
    private es.brasatech.fastbite.application.table.TableSignatureUtil tableSignatureUtil;

    @MockitoBean
    private es.brasatech.fastbite.application.discount.DiscountService discountService;

    @MockitoBean
    private es.brasatech.fastbite.application.order.OrderPricingService orderPricingService;

    @MockitoBean
    private es.brasatech.fastbite.application.order.OrderService orderService;

    @MockitoBean
    private es.brasatech.fastbite.application.settings.RestaurantSettingsService settingsService;

    @MockitoBean
    private es.brasatech.fastbite.service.OrderCheckoutService orderCheckoutService;

    @MockitoBean
    private TenantLocationService tenantLocationService;

    @MockitoBean
    private es.brasatech.fastbite.application.office.UserService userService;

    @BeforeEach
    void setUp() {
        es.brasatech.fastbite.config.RegisteredTenants.all(tenantLocationService);
        HashMap<String, String> dictionary = new HashMap<>();
        dictionary.put("cartEmpty", "Cart empty");
        dictionary.put("confirmAndSubmitBtn", "Confirm and Submit Order");
        dictionary.put("total", "Total");
        dictionary.put("subtotal", "Subtotal");
        dictionary.put("tax", "Tax");
        dictionary.put("orderConfirmation", "Order Confirmation");
        dictionary.put("orderTotal", "Order Total");
        dictionary.put("orderItems", "Order Items");

        when(menuDataService.buildMenuData(any(Locale.class)))
                .thenReturn(new MenuData(new HashMap<>(), List.of(), "", dictionary, List.of(), ""));
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        when(tenantLocationService.isOwnerOf("kebabowner", "kebab")).thenReturn(true);
    }

    private static MockHttpSession sessionFor(String username, String homeTenantId, String... roles) {
        var authorities = AuthorityUtils.createAuthorityList(roles);
        var user = new TenantUser(username, "hash", true, authorities, homeTenantId);
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT",
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities)));
        return session;
    }

    @Test
    @DisplayName("Owner visiting a restaurant they own sees all staff tabs")
    void ownerGetsStaffAccessInOwnedRestaurant() throws Exception {
        mockMvc.perform(get("/menu")
                        .header("Host", "kebab.localhost:8080")
                        .session(sessionFor("kebabowner", null, "ROLE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("kebabowner")))
                .andExpect(content().string(containsString("/kebab/dashboard")))
                .andExpect(content().string(containsString("/kebab/counter")))
                .andExpect(content().string(containsString("/kebab/backoffice")));
    }

    @Test
    @DisplayName("Owner cannot administer a restaurant they don't own")
    void ownerIsAnonymousInOtherRestaurants() throws Exception {
        mockMvc.perform(get("/burger/backoffice")
                        .header("Host", "localhost:8080")
                        .session(sessionFor("kebabowner", null, "ROLE_OWNER", "ROLE_ADMIN")))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/burger/login"));
    }

    @Test
    @DisplayName("Admin of another restaurant is sent to this restaurant's login")
    void foreignStaffCannotAdministerThisRestaurant() throws Exception {
        mockMvc.perform(get("/backoffice")
                        .header("Host", "kebab.localhost:8080")
                        .session(sessionFor("admin", "burger", "ROLE_ADMIN")))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("Admin of this restaurant passes security")
    void ownStaffPassesSecurity() throws Exception {
        // No back-office controller in this slice: 404 means the request got past security.
        mockMvc.perform(get("/backoffice")
                        .header("Host", "kebab.localhost:8080")
                        .session(sessionFor("admin", "kebab", "ROLE_ADMIN")))
                .andExpect(status().isNotFound());
    }
}
