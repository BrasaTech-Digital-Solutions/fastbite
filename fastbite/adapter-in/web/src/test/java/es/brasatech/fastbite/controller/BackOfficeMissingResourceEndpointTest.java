package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.discount.DiscountService;
import es.brasatech.fastbite.application.office.*;
import es.brasatech.fastbite.application.payment.PaymentService;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.security.TenantUser;
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

import java.util.Optional;
import java.util.List;
import es.brasatech.fastbite.domain.payment.PaymentConfig;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(controllers = {ProductController.class, GroupController.class, CustomizationController.class,
        BackOfficeTableController.class, BackOfficeDiscountController.class, BackOfficePaymentController.class})
@ContextConfiguration(classes = {TestConfig.class, ProductController.class, GroupController.class,
        CustomizationController.class, BackOfficeTableController.class, BackOfficeDiscountController.class,
        BackOfficePaymentController.class, ResourceNotFoundAdvice.class, SecurityConfig.class, TenantRoutingResolver.class, WebConfig.class})
class BackOfficeMissingResourceEndpointTest {
    @Autowired MockMvc mvc;
    @MockitoBean ProductService products;
    @MockitoBean GroupService groups;
    @MockitoBean CustomizationService customizations;
    @MockitoBean TableService tables;
    @MockitoBean DiscountService discounts;
    @MockitoBean PaymentService payments;
    @MockitoBean TenantLocationService locations;
    private static final String[] IDS = {"95db2bb6-0415-4c0b-8e0a-ed6d8c9fbba9", "not-a-uuid", "861f9a4f-16f2-4d98-8513-15e89e03d231"};

    @BeforeEach void setup() {
        when(locations.getLocation(anyString())).thenReturn(Optional.empty());
        when(locations.getLocation("kebab")).thenReturn(Optional.of(new TenantLocation(null, "owner", "kebab", "FREE", null)));
        when(locations.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
    }
    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder req) {
        var authorities = AuthorityUtils.createAuthorityList("ROLE_ADMIN");
        var user = new TenantUser("admin", "hash", true, authorities, "kebab");
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities)));
        return req.session(session).header("Referer", "http://localhost/kebab/backoffice").with(csrf());
    }
    @Test void catalogMissingIds() throws Exception {
        for (String id : IDS) {
            for (String kind : new String[]{"products", "groups", "customizations"}) {
                String path = "/api/backoffice/" + kind + "/" + id;
                mvc.perform(admin(get(path))).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Resource not found"));
                String body = switch (kind) { case "products" -> "{\"active\":true}"; case "customizations" -> "{\"usageCount\":0}"; default -> "{}"; };
                mvc.perform(admin(put(path).contentType(MediaType.APPLICATION_JSON).content(body))).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Resource not found"));
                mvc.perform(admin(delete(path))).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Resource not found"));
            }
            mvc.perform(admin(post("/api/backoffice/products/" + id + "/toggle-86"))).andExpect(status().isNotFound());
        }
    }
    @Test void tableAndDiscountMissingIds() throws Exception {
        for (String id : IDS) {
            for (String kind : new String[]{"tables", "discounts"}) {
                String path = "/api/backoffice/" + kind + "/" + id;
                String body = kind.equals("tables") ? "{\"seats\":1,\"active\":true}" : "{\"active\":true,\"accumulative\":false,\"applyOnCounter\":false}";
                mvc.perform(admin(put(path).contentType(MediaType.APPLICATION_JSON).content(body)))
                        .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Resource not found"));
                mvc.perform(admin(delete(path))).andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.message").value("Resource not found"));
            }
        }
    }
    @Test void paymentMissingIds() throws Exception {
        for (String id : IDS) {
            mvc.perform(admin(post("/api/backoffice/payment/" + id + "/active"))).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Resource not found"));
            mvc.perform(admin(delete("/api/backoffice/payment/" + id))).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Resource not found"));
            verify(payments, never()).setActiveConfig(id);
            verify(payments, never()).deleteConfig(id);
        }
    }
    @Test void paymentExistingIdStillWorks() throws Exception {
        String id = "existing";
        when(payments.findConfigById(id)).thenReturn(Optional.of(new PaymentConfig(id, List.of(), List.of(), false)));
        mvc.perform(admin(post("/api/backoffice/payment/" + id + "/active"))).andExpect(status().isOk());
        mvc.perform(admin(delete("/api/backoffice/payment/" + id))).andExpect(status().isOk());
        verify(payments).setActiveConfig(id);
        verify(payments).deleteConfig(id);
    }
}
