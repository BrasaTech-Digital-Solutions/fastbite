package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.settings.RestaurantSettingsService;
import es.brasatech.fastbite.application.tenant.PlanEntitlementService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
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

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BackOfficeController.class)
@ContextConfiguration(classes = {TestConfig.class, BackOfficeController.class, SecurityConfig.class,
        TenantRoutingResolver.class, WebConfig.class})
class RestaurantSettingsEndpointTest {

    private static final String SETTINGS = "{\"dineIn\":true,\"takeaway\":false,\"kdsYellowMinutes\":5,\"kdsRedMinutes\":10}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private es.brasatech.fastbite.application.fiscal.FiscalProfileService fiscalProfileService;

    @MockitoBean
    private RestaurantSettingsService settingsService;
    @MockitoBean
    private PlanEntitlementService planEntitlementService;
    @MockitoBean
    private TenantLocationService tenantLocationService;

    @BeforeEach
    void setUp() {
        es.brasatech.fastbite.config.RegisteredTenants.all(tenantLocationService);
        when(fiscalProfileService.defaultVatRate()).thenReturn(new java.math.BigDecimal("10.00"));
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        when(settingsService.get()).thenReturn(RestaurantSettings.DEFAULTS);
        when(settingsService.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private static MockHttpSession kebabStaff(String role) {
        var authorities = AuthorityUtils.createAuthorityList(role);
        var user = new TenantUser("staff", "hash", true, authorities, "kebab");
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT",
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities)));
        return session;
    }

    @Test
    void theBackOfficeReadsTheRestaurantsSettings() throws Exception {
        mockMvc.perform(get("/api/backoffice/settings").session(kebabStaff("ROLE_ADMIN"))
                        .header("Referer", "http://localhost/kebab/backoffice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dineIn").value(true))
                .andExpect(jsonPath("$.takeaway").value(true))
                .andExpect(jsonPath("$.kdsYellowMinutes").value(0));
    }

    @Test
    void anAdminSavesTheSettings() throws Exception {
        mockMvc.perform(put("/api/backoffice/settings").session(kebabStaff("ROLE_ADMIN")).with(csrf())
                        .header("Referer", "http://localhost/kebab/backoffice")
                        .contentType(MediaType.APPLICATION_JSON).content(SETTINGS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.takeaway").value(false));

        verify(settingsService).save(new RestaurantSettings(true, false, 5, 10));
    }

    @Test
    void aCashierCannotChangeTheSettings() throws Exception {
        mockMvc.perform(put("/api/backoffice/settings").session(kebabStaff("ROLE_CASHIER")).with(csrf())
                        .header("Referer", "http://localhost/kebab/counter")
                        .contentType(MediaType.APPLICATION_JSON).content(SETTINGS))
                .andExpect(status().isForbidden());

        verify(settingsService, never()).save(any());
    }
}
