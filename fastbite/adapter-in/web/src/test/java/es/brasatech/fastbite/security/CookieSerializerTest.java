package es.brasatech.fastbite.security;

import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.filter.ForwardedHeaderFilter;

import static org.junit.jupiter.api.Assertions.*;

public class CookieSerializerTest {

    private static final SessionCookie PRODUCTION = new SessionCookie(new PlatformEnvironment("production"), "", "");

    /** The Set-Cookie header written for a request to the given host. */
    private static String setCookie(SessionCookie cookie, String host) {
        CookieSerializer serializer = new SecurityConfig().cookieSerializer(cookie);
        assertTrue(serializer instanceof DefaultCookieSerializer);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName(host);
        MockHttpServletResponse response = new MockHttpServletResponse();
        serializer.writeCookieValue(new CookieSerializer.CookieValue(request, response, "SESSION-123"));
        String header = response.getHeader("Set-Cookie");
        assertNotNull(header);
        return header;
    }

    @Test
    public void testCookieSerializerDomainOnLocalhost() {
        // Without configured domain, cookie is host-only (no Domain attribute)
        assertFalse(setCookie(PRODUCTION, "localhost").contains("Domain="),
                "Set-Cookie should not contain Domain attribute on localhost");
    }

    @Test
    public void testCookieSerializerDomainOnSubdomainLocalhost() {
        // Without configured domain, subdomains also get host-only cookie
        assertFalse(setCookie(PRODUCTION, "kebab.localhost").contains("Domain="),
                "Set-Cookie should not contain Domain attribute on kebab.localhost");
    }

    @Test
    public void testCookieSerializerDomainOnProductionSubdomain() {
        // Without configured domain, hosts get host-only cookie
        assertFalse(setCookie(PRODUCTION, "kebab.fastbite.com").contains("Domain="),
                "Set-Cookie should not contain Domain attribute without configured domain");
    }

    @Test
    public void theSandboxAndProductionNeverShareASessionCookie() {
        SessionCookie sandbox = new SessionCookie(new PlatformEnvironment("sandbox"), "", "");

        assertTrue(setCookie(PRODUCTION, "localhost").startsWith("FASTBITE_SESSION="));
        assertTrue(setCookie(sandbox, "localhost").startsWith("FASTBITE_SANDBOX_SESSION="));
        assertEquals("CUSTOM", new SessionCookie(new PlatformEnvironment("sandbox"), " CUSTOM ", "").name());
        assertEquals("__session", new SessionCookie(new PlatformEnvironment("production"), "__session", "").name());
    }

    @Test
    public void aConfiguredDomainSharesTheCookieWithItsSubdomainsOnly() {
        SessionCookie platform = new SessionCookie(new PlatformEnvironment("production"), "", ".FastBite.Brasatech.es");

        assertTrue(setCookie(platform, "fastbite.brasatech.es").contains("Domain=fastbite.brasatech.es"));
        assertTrue(setCookie(platform, "kebab.fastbite.brasatech.es").contains("Domain=fastbite.brasatech.es"));
        assertFalse(setCookie(platform, "soberana.brasatech.es").contains("Domain="),
                "another site of the company domain gets no shared cookie");
        assertFalse(setCookie(platform, "kebabhouse.es").contains("Domain="),
                "a restaurant's own domain keeps its cookie to itself");
        assertFalse(setCookie(platform, "evilfastbite.brasatech.es").contains("Domain="));
    }

    @Test
    public void secureAttributeIsSetWhenForwardedProtoIsHttps() throws Exception {
        CookieSerializer serializer = new SecurityConfig().cookieSerializer(PRODUCTION);
        MockHttpServletRequest rawRequest = new MockHttpServletRequest("GET", "/");
        rawRequest.setServerName("internal.run.app");
        rawRequest.addHeader("Host", "internal.run.app");
        rawRequest.addHeader("X-Forwarded-Host", "fastbite.brasatech.es");
        rawRequest.addHeader("X-Forwarded-Proto", "https");

        ForwardedHeaderFilter filter = new ForwardedHeaderFilter();
        HttpServletRequest[] filtered = new HttpServletRequest[1];
        filter.doFilter(rawRequest, new MockHttpServletResponse(), (req, res) -> {
            filtered[0] = (HttpServletRequest) req;
        });

        MockHttpServletResponse response = new MockHttpServletResponse();
        serializer.writeCookieValue(new CookieSerializer.CookieValue(filtered[0], response, "SESSION-SECURE"));
        String header = response.getHeader("Set-Cookie");
        assertNotNull(header);
        assertTrue(header.contains("Secure"), "Set-Cookie should contain Secure when forwarded proto is https");
        assertFalse(header.contains("Domain="), "Default without domain should be host-only");
    }
}
