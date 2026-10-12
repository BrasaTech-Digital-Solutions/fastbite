package es.brasatech.fastbite.security;

import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

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
        // On localhost, we must NOT set any Domain attribute (to avoid Chrome rejection and allow host-only login)
        assertFalse(setCookie(PRODUCTION, "localhost").contains("Domain="),
                "Set-Cookie should not contain Domain attribute on localhost");
    }

    @Test
    public void testCookieSerializerDomainOnSubdomainLocalhost() {
        assertTrue(setCookie(PRODUCTION, "kebab.localhost").contains("Domain=kebab.localhost"),
                "Set-Cookie should contain Domain=kebab.localhost");
    }

    @Test
    public void testCookieSerializerDomainOnProductionSubdomain() {
        // Without a configured domain, kebab.fastbite.com shares the cookie with fastbite.com
        assertTrue(setCookie(PRODUCTION, "kebab.fastbite.com").contains("Domain=fastbite.com"),
                "Set-Cookie should contain Domain=fastbite.com");
    }

    @Test
    public void theSandboxAndProductionNeverShareASessionCookie() {
        SessionCookie sandbox = new SessionCookie(new PlatformEnvironment("sandbox"), "", "");

        assertTrue(setCookie(PRODUCTION, "localhost").startsWith("FASTBITE_SESSION="));
        assertTrue(setCookie(sandbox, "localhost").startsWith("FASTBITE_SANDBOX_SESSION="));
        assertEquals("CUSTOM", new SessionCookie(new PlatformEnvironment("sandbox"), " CUSTOM ", "").name());
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
}
