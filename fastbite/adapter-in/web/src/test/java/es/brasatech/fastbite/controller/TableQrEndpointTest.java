package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.application.table.TableSignatureUtil;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantContextFilter;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.table.Table;
import es.brasatech.fastbite.domain.table.TableStatus;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TableQrController.class)
@ContextConfiguration(classes = {TestConfig.class, TableQrController.class, SecurityConfig.class,
        TenantRoutingResolver.class, WebConfig.class, es.brasatech.fastbite.config.PublicOrigin.class})
class TableQrEndpointTest {

    private final TableSignatureUtil signatures = new TableSignatureUtil("a-secret-for-tests");

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockitoBean
    private TableService tableService;
    @MockitoBean
    private TableSignatureUtil tableSignatureUtil;
    @MockitoBean
    private TenantLocationService tenantLocationService;

    @BeforeEach
    void setUp() {
        es.brasatech.fastbite.config.RegisteredTenants.all(tenantLocationService);
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilters(new ForwardedHeaderFilter(),
                        webApplicationContext.getBean(TenantContextFilter.class))
                .apply(springSecurity())
                .build();
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        when(tableSignatureUtil.generateSignature(anyString(), anyString()))
                .thenAnswer(call -> signatures.generateSignature(call.getArgument(0), call.getArgument(1)));
        when(tableService.findAll()).thenReturn(List.of(
                new Table("t1", "Table 1", 4, TableStatus.AVAILABLE, true, List.of()),
                new Table("t2", "Terrace", 2, TableStatus.OCCUPIED, true, List.of()),
                new Table("t3", "Storage", 2, TableStatus.AVAILABLE, false, List.of())));
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
    void eachActiveTableGetsASignedLinkToTheMenu() throws Exception {
        mockMvc.perform(get("/kebab/backoffice/tables/qr-codes").session(kebabStaff("ROLE_ADMIN"))
                        .header("Host", "fastbite.example")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("codes", hasSize(2)))
                .andExpect(content().string(containsString(
                        "https://fastbite.example/kebab/menu?table=t1&amp;token=" + signatures.generateSignature("kebab", "t1"))))
                .andExpect(content().string(containsString("Terrace")))
                .andExpect(content().string(not(containsString("Storage"))))
                .andExpect(content().string(not(containsString("local test address"))));
    }

    @Test
    void oneTableCanBePrintedOnItsOwnAndAnUnknownOneIsNotFound() throws Exception {
        mockMvc.perform(get("/kebab/backoffice/tables/qr-codes").param("table", "t2").session(kebabStaff("ROLE_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("codes", hasSize(1)));
        mockMvc.perform(get("/kebab/backoffice/tables/qr-codes").param("table", "nope").session(kebabStaff("ROLE_ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void codesPrintedFromALocalAddressComeWithAWarning() throws Exception {
        mockMvc.perform(get("/kebab/backoffice/tables/qr-codes").session(kebabStaff("ROLE_ADMIN"))
                        .header("Host", "localhost:8080"))
                .andExpect(content().string(containsString("local test address")));
    }

    @Test
    void cashiersCannotPrintCodes() throws Exception {
        mockMvc.perform(get("/kebab/backoffice/tables/qr-codes").session(kebabStaff("ROLE_CASHIER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void forwardedHeadersDetermineSchemeAndHostAndPort() throws Exception {
        mockMvc.perform(get("/kebab/backoffice/tables/qr-codes").session(kebabStaff("ROLE_ADMIN"))
                        .header("Host", "fastbite-xyz.a.run.app")
                        .header("X-Forwarded-Host", "fastbite.brasatech.es")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "https://fastbite.brasatech.es/kebab/menu?table=t1&amp;token=" + signatures.generateSignature("kebab", "t1"))));

        mockMvc.perform(get("/kebab/backoffice/tables/qr-codes").session(kebabStaff("ROLE_ADMIN"))
                        .header("Host", "fastbite-xyz.a.run.app")
                        .header("X-Forwarded-Host", "fastbite.localhost:8088")
                        .header("X-Forwarded-Proto", "http"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "http://fastbite.localhost:8088/kebab/menu?table=t1&amp;token=" + signatures.generateSignature("kebab", "t1"))));
    }
}
