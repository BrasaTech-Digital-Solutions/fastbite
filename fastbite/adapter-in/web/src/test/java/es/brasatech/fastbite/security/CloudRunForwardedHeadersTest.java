package es.brasatech.fastbite.security;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.application.table.TableSignatureUtil;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.PublicOrigin;
import es.brasatech.fastbite.config.TenantContextFilter;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.controller.TableQrController;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@ContextConfiguration(classes = {
        TestConfig.class,
        SecurityConfig.class,
        TenantRoutingResolver.class,
        WebConfig.class,
        PublicOrigin.class,
        TableQrController.class
})
@DisplayName("Cloud Run Forwarded Headers & Relative Redirects Tests")
class CloudRunForwardedHeadersTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockitoBean
    private TenantLocationService tenantLocationService;

    @MockitoBean
    private es.brasatech.fastbite.application.tenant.PendingSignupService pendingSignup;

    @MockitoBean
    private UserDetailsServiceImpl userDetails;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwords;

    @MockitoBean
    private TableService tableService;

    @MockitoBean
    private TableSignatureUtil tableSignatureUtil;

    @BeforeEach
    void setUp() {
        es.brasatech.fastbite.config.RegisteredTenants.all(tenantLocationService);
        ForwardedHeaderFilter forwardedHeaderFilter = new ForwardedHeaderFilter();
        forwardedHeaderFilter.setRelativeRedirects(true);

        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilters(forwardedHeaderFilter, webApplicationContext.getBean(TenantContextFilter.class))
                .apply(springSecurity())
                .build();

        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new org.springframework.security.core.userdetails.UsernameNotFoundException("not found"))
                .when(userDetails).loadUserByUsername(anyString());
    }

    @Test
    void pendingSignupLoginRequiresPasswordAndOriginalBrowser() throws Exception {
        var signup = new es.brasatech.fastbite.application.tenant.PendingSignupPort.Signup("place", "newowner", "Owner",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("correct password"), "RESTAURANT",
                "owner@example.test", "en", "v1", java.time.Instant.now(), false);
        when(pendingSignup.forBrowser("pending-id")).thenReturn(Optional.of(new es.brasatech.fastbite.application.tenant.PendingSignupPort.Pending("pending-id", signup, java.time.Instant.now().plusSeconds(60))));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("pendingSignupId", "pending-id"); session.setAttribute("pendingSignupReal", true);
        mockMvc.perform(post("/login").with(csrf()).session(session).param("username", "newowner").param("password", "correct password"))
                .andExpect(header().string("Location", "/signup/check-email"));
        mockMvc.perform(post("/login").with(csrf()).session(session).param("username", "newowner").param("password", "wrong password"))
                .andExpect(header().string("Location", "/login?error"));
        mockMvc.perform(post("/login").with(csrf()).param("username", "newowner").param("password", "correct password"))
                .andExpect(header().string("Location", "/login?error"));
    }

    @Test
    void successfulLoginReturnsToSavedPathOnBrowserOrigin() throws Exception {
        var roles = AuthorityUtils.createAuthorityList("ROLE_OWNER");
        org.mockito.Mockito.doReturn(new TenantUser("savedowner", passwords.encode("local password"), true, roles, null))
                .when(userDetails).loadUserByUsername("savedowner");
        var session = new MockHttpSession();
        mockMvc.perform(get("/owner/console").queryParam("view", "settings").session(session)
                        .header("Host", "production:8080").header("X-Forwarded-Proto", "https"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/login").with(csrf()).session(session)
                        .header("Host", "production:8080").header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "fastbite.example.test")
                        .param("username", "savedowner").param("password", "local password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/owner/console?view=settings")))
                .andExpect(header().string("Location", not(containsString("production"))));
    }

    @Test
    void savedNetworkPathCannotRedirectToAnotherHost() throws Exception {
        var roles = AuthorityUtils.createAuthorityList("ROLE_OWNER");
        org.mockito.Mockito.doReturn(new TenantUser("savedowner", passwords.encode("local password"), true, roles, null))
                .when(userDetails).loadUserByUsername("savedowner");
        var saved = org.mockito.Mockito.mock(org.springframework.security.web.savedrequest.SavedRequest.class);
        when(saved.getRedirectUrl()).thenReturn("https://internal.example//attacker.example/path");
        var session = new MockHttpSession(); session.setAttribute("SPRING_SECURITY_SAVED_REQUEST", saved);
        mockMvc.perform(post("/login").with(csrf()).session(session)
                        .header("Host", "production:8080").header("X-Forwarded-Host", "fastbite.example.test")
                        .header("X-Forwarded-Proto", "https").param("username", "savedowner").param("password", "local password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/owner/console"));
    }

    @Test
    @DisplayName("Unauthenticated request behind Cloud Run gets relative redirect to /login (no internal run.app host)")
    void unauthenticatedRequestGetsRelativeRedirect() throws Exception {
        mockMvc.perform(get("/owner/console")
                        .header("Host", "fastbite-xpbnjwdgda-no.a.run.app")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/login"))
                .andExpect(header().string("Location", not(containsString("run.app"))));
    }

    @Test
    @DisplayName("Unauthenticated request to tenant counter behind Cloud Run gets relative redirect to /{tenant}/login")
    void unauthenticatedTenantCounterGetsRelativeRedirect() throws Exception {
        mockMvc.perform(get("/cake/counter")
                        .header("Host", "fastbite-xpbnjwdgda-no.a.run.app")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/cake/login"))
                .andExpect(header().string("Location", not(containsString("run.app"))));
    }

    @Test
    @DisplayName("Failed login behind Cloud Run redirects relatively to /login?error")
    void authenticationFailureRedirectsRelatively() throws Exception {
        mockMvc.perform(post("/login").with(csrf())
                        .header("Host", "fastbite-xpbnjwdgda-no.a.run.app")
                        .header("X-Forwarded-Proto", "https")
                        .param("username", "baduser")
                        .param("password", "badpass"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/login?error"))
                .andExpect(header().string("Location", not(containsString("run.app"))));
    }

    @Test
    @DisplayName("Failed tenant login behind Cloud Run redirects relatively to /{tenant}/login?error")
    void tenantAuthenticationFailureRedirectsRelatively() throws Exception {
        mockMvc.perform(post("/login?tenantId=cake").with(csrf())
                        .header("Host", "fastbite-xpbnjwdgda-no.a.run.app")
                        .header("X-Forwarded-Proto", "https")
                        .param("username", "baduser")
                        .param("password", "badpass"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/cake/login?error"))
                .andExpect(header().string("Location", not(containsString("run.app"))));
    }

    @Test
    @DisplayName("Sign out behind Cloud Run redirects relatively to platform target (/signup)")
    void logoutRedirectsRelatively() throws Exception {
        mockMvc.perform(post("/logout").with(csrf())
                        .header("Host", "fastbite-xpbnjwdgda-no.a.run.app")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/signup"))
                .andExpect(header().string("Location", not(containsString("run.app"))));
    }

    @Test
    @DisplayName("Tenant sign out behind Cloud Run redirects relatively to /{tenant}/menu")
    void tenantLogoutRedirectsRelatively() throws Exception {
        mockMvc.perform(post("/cake/logout").with(csrf())
                        .header("Host", "fastbite-xpbnjwdgda-no.a.run.app")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/cake/menu"))
                .andExpect(header().string("Location", not(containsString("run.app"))));
    }

    @Test
    @DisplayName("Session cookie gets Secure attribute behind Cloud Run via X-Forwarded-Proto without X-Forwarded-Host")
    void sessionCookieIsSecureWithCloudRunForwardedProto() throws Exception {
        SessionCookie sessionCookie = new SessionCookie(new PlatformEnvironment("production"), "__session", "");
        CookieSerializer serializer = new SecurityConfig().cookieSerializer(sessionCookie);

        MockHttpServletRequest rawRequest = new MockHttpServletRequest("GET", "/owner/console");
        rawRequest.setServerName("fastbite-xpbnjwdgda-no.a.run.app");
        rawRequest.addHeader("Host", "fastbite-xpbnjwdgda-no.a.run.app");
        rawRequest.addHeader("X-Forwarded-Proto", "https");

        ForwardedHeaderFilter filter = new ForwardedHeaderFilter();
        HttpServletRequest[] filtered = new HttpServletRequest[1];
        filter.doFilter(rawRequest, new MockHttpServletResponse(), (req, res) -> filtered[0] = (HttpServletRequest) req);

        MockHttpServletResponse response = new MockHttpServletResponse();
        serializer.writeCookieValue(new CookieSerializer.CookieValue(filtered[0], response, "TEST-SESSION-ID"));

        String cookieHeader = response.getHeader("Set-Cookie");
        assertNotNull(cookieHeader);
        assertTrue(cookieHeader.contains("__session="));
        assertTrue(cookieHeader.contains("Secure"), "Cookie must have Secure flag behind Cloud Run");
        assertFalse(cookieHeader.contains("Domain="), "Cookie must remain host-only");
    }

    @Test
    @DisplayName("PublicOrigin resolves configured public URL and ignores internal Cloud Run host")
    void publicOriginUsesConfiguredUrlOverIncomingHost() {
        PublicOrigin publicOrigin = new PublicOrigin("https://fastbite.brasatech.es/");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("fastbite-xpbnjwdgda-no.a.run.app");
        request.setServerPort(443);

        assertEquals("https://fastbite.brasatech.es", publicOrigin.resolve(request));
        assertFalse(publicOrigin.isLocal(request));
    }

    @Test
    @DisplayName("PublicOrigin falls back to incoming request when fastbite.public-url is not set")
    void publicOriginFallbackWhenUnconfigured() {
        PublicOrigin publicOrigin = new PublicOrigin("");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8080);

        assertEquals("http://localhost:8080", publicOrigin.resolve(request));
        assertTrue(publicOrigin.isLocal(request));
    }

    @Test
    @DisplayName("TableQr and Stripe URLs use configured PublicOrigin even when request has internal Cloud Run host")
    void urlsUseConfiguredPublicOriginBehindCloudRun() {
        PublicOrigin publicOrigin = new PublicOrigin("https://fastbite.brasatech.es");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/kebab/backoffice/tables/qr-codes");
        request.setScheme("https");
        request.setServerName("fastbite-xpbnjwdgda-no.a.run.app");
        request.setServerPort(443);
        request.setAttribute(TenantRoutingResolver.TENANT_URL_PREFIX, "/kebab");

        String origin = publicOrigin.resolve(request);
        assertEquals("https://fastbite.brasatech.es", origin);

        String menuUrl = origin + request.getAttribute(TenantRoutingResolver.TENANT_URL_PREFIX) + "/menu";
        assertEquals("https://fastbite.brasatech.es/kebab/menu", menuUrl);

        String stripeReturnUrl = origin + request.getAttribute(TenantRoutingResolver.TENANT_URL_PREFIX) + "/order-confirmation";
        assertEquals("https://fastbite.brasatech.es/kebab/order-confirmation", stripeReturnUrl);
    }
}
