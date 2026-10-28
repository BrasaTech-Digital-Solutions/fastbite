package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.office.CustomizationService;
import es.brasatech.fastbite.application.office.GroupService;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantContextFilter;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.product.ProductDto;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.security.TenantUser;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The counter works for every counter role, and an address naming a restaurant that doesn't exist answers 404. */
@WebMvcTest(controllers = {CounterCatalogController.class, ProductController.class})
@ContextConfiguration(classes = {TestConfig.class, CounterCatalogController.class, ProductController.class,
        SecurityConfig.class, TenantRoutingResolver.class, WebConfig.class})
class CounterCatalogEndpointTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ProductService productService;
    @MockitoBean private GroupService groupService;
    @MockitoBean private CustomizationService customizationService;
    @MockitoBean private TableService tableService;
    @MockitoBean private TenantLocationService tenantLocationService;

    @BeforeEach
    void setUp() {
        when(tenantLocationService.getLocation(anyString())).thenReturn(Optional.empty());
        when(tenantLocationService.getLocation("kebab"))
                .thenReturn(Optional.of(new TenantLocation(null, "owner", "kebab", "FREE", null)));
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        when(productService.findAll()).thenReturn(List.of(new ProductDto("kebab", "Kebab", new BigDecimal("6.50"),
                "Beef kebab", "/kebab.webp", Set.of(), true)));
    }

    private static MockHttpServletRequestBuilder as(String role, MockHttpServletRequestBuilder request) {
        var authorities = AuthorityUtils.createAuthorityList("ROLE_" + role);
        var user = new TenantUser("carla", "hash", true, authorities, "kebab");
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT",
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities)));
        return request.session(session).header("Referer", "http://localhost/kebab/counter");
    }

    @Test void everyCounterRoleReadsTheMenuForTheCounter() throws Exception {
        for (String role : new String[]{"CASHIER", "WAITER", "COOK", "MANAGER", "ADMIN"}) {
            mockMvc.perform(as(role, get("/counter/api/catalog/products")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value("kebab"));
            for (String kind : new String[]{"groups", "customizations", "tables"}) {
                mockMvc.perform(as(role, get("/counter/api/catalog/" + kind))).andExpect(status().isOk());
            }
        }
    }

    @Test void theCatalogNeedsASignedInUser() throws Exception {
        mockMvc.perform(get("/counter/api/catalog/products").header("Referer", "http://localhost/kebab/counter"))
                .andExpect(status().is3xxRedirection());
    }

    @Test void aHostNamingAnUnknownRestaurantAnswers404() throws Exception {
        mockMvc.perform(get("/menu").with(request -> { request.setServerName("unknown.localhost"); return request; }))
                .andExpect(status().isNotFound())
                .andExpect(request().attribute(TenantContextFilter.UNKNOWN_RESTAURANT, true));
    }

    @Test void theBackOfficeStaysClosedToCashiers() throws Exception {
        mockMvc.perform(as("CASHIER", get("/api/backoffice/products"))).andExpect(status().isForbidden());
        mockMvc.perform(as("MANAGER", get("/api/backoffice/products"))).andExpect(status().isOk());
    }

    @Test void anAddressNamingAnUnknownRestaurantAnswers404() throws Exception {
        mockMvc.perform(get("/unknown/menu"))
                .andExpect(status().isNotFound())
                .andExpect(request().attribute(TenantContextFilter.UNKNOWN_RESTAURANT, true));
        mockMvc.perform(as("CASHIER", get("/unknown/counter"))).andExpect(status().isNotFound());
    }

    @Test void anUnknownRestaurantOnlyHintedAtByTheRefererIsIgnored() throws Exception {
        // The tenant would come from the Referer: without one, the request is a platform request
        mockMvc.perform(get("/counter/api/catalog/products").header("Referer", "http://localhost/unknown/counter"))
                .andExpect(request().attribute(TenantContextFilter.UNKNOWN_RESTAURANT, (Object) null));
    }

    @Test void emptyCustomizationsAreExcludedFromCounterCatalog() throws Exception {
        var opt = new es.brasatech.fastbite.domain.customization.CustomizationOptionDto("opt-1", "Regular", BigDecimal.ZERO);
        var valid = new es.brasatech.fastbite.domain.customization.CustomizationDto("c-valid", "Valid", "radio", List.of(opt), 0);
        var empty = new es.brasatech.fastbite.domain.customization.CustomizationDto("c-empty", "Empty", "quantity", List.of(), 0);
        when(customizationService.findAll()).thenReturn(List.of(valid, empty));

        mockMvc.perform(as("CASHIER", get("/counter/api/catalog/customizations")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("c-valid"));
    }
}
