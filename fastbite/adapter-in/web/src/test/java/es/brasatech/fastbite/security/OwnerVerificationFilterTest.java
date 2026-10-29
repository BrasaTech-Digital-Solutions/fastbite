package es.brasatech.fastbite.security;

import es.brasatech.fastbite.application.office.OwnerEmailPort;
import es.brasatech.fastbite.application.office.OwnerEmailService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Locale;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OwnerVerificationFilterTest {
    OwnerEmailService owners = mock(OwnerEmailService.class);
    StaticMessageSource messages = new StaticMessageSource();
    OwnerVerificationFilter filter = new OwnerVerificationFilter(owners, new PlatformEnvironment("production"), messages);
    @AfterEach void clear() { SecurityContextHolder.clearContext(); org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
    private void login(String username, String tenant, String role) {
        var roles = AuthorityUtils.createAuthorityList(role);
        var principal = new TenantUser(username, "hash", true, roles, tenant);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, roles));
    }
    private void login() { login("owner", null, "ROLE_OWNER"); }
    @Test void blocksProtectedRequestsButAllowsVerificationAndLegalPages() throws Exception {
        login(); when(owners.status("owner")).thenReturn(Optional.of(new OwnerEmailPort.Status("id", null, null, false)));
        var request = new MockHttpServletRequest("GET", "/owner/console");
        var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getRedirectedUrl()).isEqualTo("/owner/account/verify");
        for (String path : new String[]{"/owner/account/verify", "/legal/terms", "/verifactu/declaracion-responsable", "/verifactu/declaracion-responsable/v1.pdf", "/favicon.ico"}) {
            chain = new MockFilterChain(); filter.doFilter(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).as(path).isNotNull();
        }
        verify(owners, times(1)).status("owner");
    }
    @Test void verifiedOwnerIsLookedUpOncePerSessionAndCacheIsBoundToUsername() throws Exception {
        login(); when(owners.status("owner")).thenReturn(Optional.of(new OwnerEmailPort.Status("id", "a@example.test", null, true)));
        var session = new MockHttpSession();
        for (int i = 0; i < 2; i++) {
            var request = new MockHttpServletRequest("GET", "/owner/console"); request.setSession(session);
            var chain = new MockFilterChain(); filter.doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isNotNull();
        }
        verify(owners, times(1)).status("owner");
        assertThat(session.getAttribute(OwnerVerificationFilter.VERIFIED_SESSION_ATTRIBUTE)).isEqualTo("owner");
        login("other", null, "ROLE_OWNER");
        var request = new MockHttpServletRequest("GET", "/owner/console"); request.setSession(session);
        var response = new MockHttpServletResponse(); filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getRedirectedUrl()).isEqualTo("/owner/account/verify");
        verify(owners).status("other");
    }
    @Test void unverifiedSessionsAreCheckedAgainAndJsonRequestsGetTranslated403() throws Exception {
        login(); messages.addMessage("account.email.required", Locale.forLanguageTag("es"), "Verifica tu correo.");
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.forLanguageTag("es"));
        when(owners.status("owner")).thenReturn(Optional.of(new OwnerEmailPort.Status("id", null, null, false)));
        var session = new MockHttpSession();
        for (String path : new String[]{"/api/order", "/cafe/api/order", "/counter/api/order", "/owner/workspace/cafe/promote/preview"}) {
            var request = new MockHttpServletRequest("POST", path); request.setSession(session); request.addPreferredLocale(Locale.forLanguageTag("es"));
            var response = new MockHttpServletResponse(); filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).as(path).isEqualTo(403);
            assertThat(response.getContentType()).startsWith("application/json");
            assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Verifica tu correo.\"}");
        }
        for (String header : new String[]{"Accept", "X-Requested-With"}) {
            var request = new MockHttpServletRequest("GET", "/owner/console"); request.setSession(session);
            request.addHeader(header, header.equals("Accept") ? "application/json" : "XMLHttpRequest");
            var response = new MockHttpServletResponse(); filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(403);
        }
        verify(owners, times(6)).status("owner");
    }
    @Test void staffAndSandboxOwnersAreNotGated() throws Exception {
        login("staff", "cafe", "ROLE_COOK");
        var chain = new MockFilterChain(); filter.doFilter(new MockHttpServletRequest("GET", "/cafe/dashboard"), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
        login(); chain = new MockFilterChain();
        new OwnerVerificationFilter(owners, new PlatformEnvironment("sandbox"), messages)
                .doFilter(new MockHttpServletRequest("GET", "/owner/console"), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull(); verifyNoInteractions(owners);
    }
}
