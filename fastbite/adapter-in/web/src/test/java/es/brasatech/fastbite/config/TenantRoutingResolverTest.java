package es.brasatech.fastbite.config;

import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TenantRoutingResolverTest {

    private final TenantLocationService tenantLocationService = mock(TenantLocationService.class);
    private final TenantRoutingResolver resolver = new TenantRoutingResolver(tenantLocationService);

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.addHeader("Host", "localhost:8080");
        return request;
    }

    @Test
    void resolvesThePathPrefix() {
        var tenant = resolver.resolve(request("/pizza/menu"));

        assertThat(tenant).isEqualTo(new TenantRoutingResolver.ResolvedTenant("pizza", false));
        assertThat(tenant.urlPrefix()).isEqualTo("/pizza");
    }

    @Test
    void resolvesThePathPrefixUnderAContextPath() {
        MockHttpServletRequest request = request("/app/pizza/menu");
        request.setContextPath("/app");

        assertThat(resolver.resolve(request).id()).isEqualTo("pizza");
    }

    @Test
    void resolvesTheSubdomainForPlatformPaths() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/menu");
        request.setServerName("kebab.localhost");
        request.setServerPort(8080);
        request.addHeader("Host", "kebab.localhost:8080");

        var tenant = resolver.resolve(request);

        assertThat(tenant).isEqualTo(new TenantRoutingResolver.ResolvedTenant("kebab", true));
        assertThat(tenant.urlPrefix()).isEmpty();
    }

    @Test
    void platformHostsAreNeverResolvedAsTenants() {
        TenantRoutingResolver platformResolver = new TenantRoutingResolver(
                tenantLocationService,
                "http://fastbite.localhost:8088",
                "https://sandbox.fastbite.localhost:8088"
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/menu");
        request.setServerName("fastbite.localhost");
        request.setServerPort(8088);
        request.addHeader("Host", "fastbite.localhost:8088");

        assertThat(platformResolver.resolve(request)).isNull();

        MockHttpServletRequest sandboxRequest = new MockHttpServletRequest("GET", "/menu");
        sandboxRequest.setServerName("sandbox.fastbite.localhost");
        sandboxRequest.setServerPort(8088);
        sandboxRequest.addHeader("Host", "sandbox.fastbite.localhost:8088");

        assertThat(platformResolver.resolve(sandboxRequest)).isNull();

        MockHttpServletRequest kebabRequest = new MockHttpServletRequest("GET", "/menu");
        kebabRequest.setServerName("kebab.localhost");
        kebabRequest.setServerPort(8088);
        kebabRequest.addHeader("Host", "kebab.localhost:8088");

        assertThat(platformResolver.resolve(kebabRequest).id()).isEqualTo("kebab");
    }

    @Test
    void forwardedHostWinsOverInternalHostHeader() throws Exception {
        ForwardedHeaderFilter filter = new ForwardedHeaderFilter();
        MockHttpServletRequest rawRequest = new MockHttpServletRequest("GET", "/menu");
        rawRequest.addHeader("Host", "internal.run.app");
        rawRequest.addHeader("X-Forwarded-Host", "kebab.localhost");
        rawRequest.addHeader("X-Forwarded-Proto", "https");

        HttpServletRequest[] filtered = new HttpServletRequest[1];
        filter.doFilter(rawRequest, new MockHttpServletResponse(), (req, res) -> {
            filtered[0] = (HttpServletRequest) req;
        });

        assertThat(resolver.resolve(filtered[0]).id()).isEqualTo("kebab");
    }

    @Test
    void resolvesABoundCustomDomainAndCachesTheLookup() {
        when(tenantLocationService.getLocationByCustomDomain("orders.pizza.es"))
                .thenReturn(Optional.of(new TenantLocation("1", "alice", "pizza", "RESTAURANT", "orders.pizza.es")));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/menu");
        request.setServerName("orders.pizza.es");
        request.addHeader("Host", "orders.pizza.es");

        assertThat(resolver.resolve(request).id()).isEqualTo("pizza");
        assertThat(resolver.resolve(request).id()).isEqualTo("pizza");
        verify(tenantLocationService, times(1)).getLocationByCustomDomain("orders.pizza.es");
    }

    @Test
    void resolvesTheTenantQueryParameter() {
        MockHttpServletRequest request = request("/login");
        request.setQueryString("tenantId=pizza");

        assertThat(resolver.resolve(request).id()).isEqualTo("pizza");
    }

    @Test
    void ignoresTenantIdFormFields() {
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        MockHttpServletRequest request = request("/signup");
        request.setMethod("POST");
        request.setParameter("tenantId", "burger"); // the restaurant being created, not the current one

        assertThat(resolver.resolve(request)).isNull();
    }

    @Test
    void resolvesTheRefererForAjaxCalls() {
        MockHttpServletRequest request = request("/api/calculate-cart");
        request.addHeader("Referer", "http://localhost:8080/pizza/menu");

        assertThat(resolver.resolve(request).id()).isEqualTo("pizza");
    }

    @Test
    void entryPagesIgnoreTheReferer() {
        MockHttpServletRequest request = request("/login");
        request.addHeader("Referer", "http://localhost:8080/pizza/menu");

        assertThat(resolver.resolve(request)).isNull();
    }

    @Test
    void platformPathsHaveNoTenant() {
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());

        assertThat(resolver.resolve(request("/menu"))).isNull();
        assertThat(resolver.resolve(request("/"))).isNull();
    }

    @Test
    void ignoresSegmentsThatCannotBeTenantIds() {
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());

        assertThat(resolver.resolve(request("/.env"))).isNull();
        assertThat(resolver.resolve(request("/favicon.ico"))).isNull();
        assertThat(resolver.resolve(request("/user-images/pizza/a.png"))).isNull();
    }
}
