package es.brasatech.fastbite.config;

import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TenantGuestRoutesTest {
    private final TenantLocationService locations = mock(TenantLocationService.class);
    private final TenantContextFilter filter = new TenantContextFilter(new TenantRoutingResolver(
            locations, "http://fastbite.localhost:8088", "http://sandbox.fastbite.localhost:8088"));

    @ParameterizedTest
    @ValueSource(strings = {"/menu", "/allergens", "/api/calculate-cart", "/api/calculate-confirmation", "/select-payment", "/api/create-order", "/api/order-status", "/api/order-status/live", "/order-confirmation", "/api/stripe/create-checkout-session"})
    void noTenantReturns404EvenWithStaleOrderSession(String path) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.setServerName("localhost");
        var session = new MockHttpSession(); session.setAttribute("orderId", "stale"); request.setSession(session);
        var response = new MockHttpServletResponse(); var called = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> called.set(true));
        assertThat(response.getStatus()).isEqualTo(404); assertThat(called).isFalse();
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/menu", "/allergens", "/api/calculate-cart", "/api/calculate-confirmation", "/select-payment", "/api/create-order", "/api/order-status", "/api/order-status/live", "/order-confirmation", "/api/stripe/create-checkout-session"})
    void customDomainAndPathResolveForEveryGuestRoute(String path) throws Exception {
        var tenant = new TenantLocation("1", "owner", "pizza", "RESTAURANT", "menu.pizza.example");
        when(locations.getLocationByCustomDomain("menu.pizza.example")).thenReturn(Optional.of(tenant));
        when(locations.getLocation("pizza")).thenReturn(Optional.of(tenant));
        for (boolean custom : new boolean[]{true, false}) {
            var request = new MockHttpServletRequest("GET", custom ? path : "/pizza" + path);
            request.setServerName(custom ? "menu.pizza.example" : "localhost");
            var response = new MockHttpServletResponse(); var called = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> {
                called.set(true);
                assertThat(TenantContext.getCurrentTenant()).isEqualTo("pizza");
                assertThat(req.getAttribute(TenantRoutingResolver.TENANT_URL_PREFIX)).isEqualTo(custom ? "" : "/pizza");
            });
            assertThat(response.getStatus()).isEqualTo(200); assertThat(called).isTrue();
            assertThat(TenantContext.getCurrentTenant()).isNull();
        }
    }
    @org.junit.jupiter.api.Test
    void unknownRestaurantAndStaleTenantHintCannotReachGuestData() throws Exception {
        for (String uri : new String[]{"/missing/menu", "/menu"}) {
            var request = new MockHttpServletRequest("GET", uri);
            request.setServerName("localhost");
            if (uri.equals("/menu")) request.setQueryString("tenantId=missing");
            var session = new MockHttpSession(); session.setAttribute("orderId", "stale"); request.setSession(session);
            var response = new MockHttpServletResponse(); var called = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> called.set(true));
            assertThat(response.getStatus()).isEqualTo(404); assertThat(called).isFalse();
        }
    }

    @org.junit.jupiter.api.Test
    void errorDispatchAndNonTenantToastAreNotBlocked() throws Exception {
        for (String uri : new String[]{"/error", "/api/toast"}) {
            var request = new MockHttpServletRequest("GET", uri); request.setServerName("localhost");
            var response = new MockHttpServletResponse(); var called = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> called.set(true));
            assertThat(called).isTrue(); assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/unknown-slug/menu", "/unknown-slug/", "/unknown-slug", "/unknown-slug/allergens",
            "/unknown-slug/counter", "/unknown-slug/dashboard", "/unknown-slug/backoffice", "/unknown-slug/login",
            "/unknown_slug/menu", "/unknown.slug/menu",
            "/unknownslug/menu", "/unknownslug/", "/unknownslug/counter"
    })
    void unknownRestaurantPathFormsReturn404WithUnknownFlag(String uri) throws Exception {
        var request = new MockHttpServletRequest("GET", uri);
        request.setServerName("fastbite.localhost");
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> called.set(true));
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(request.getAttribute(TenantContextFilter.UNKNOWN_RESTAURANT)).isEqualTo(Boolean.TRUE);
        assertThat(called).isFalse();
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown-slug.localhost", "unknownslug.localhost", "unknown-slug.fastbite.com"})
    void unknownRestaurantHostAliasReturns404WithUnknownFlag(String host) throws Exception {
        for (String path : new String[]{"/menu", "/counter", "/"}) {
            var request = new MockHttpServletRequest("GET", path);
            request.setServerName(host);
            var response = new MockHttpServletResponse();
            var called = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> called.set(true));
            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(request.getAttribute(TenantContextFilter.UNKNOWN_RESTAURANT)).isEqualTo(Boolean.TRUE);
            assertThat(called).isFalse();
            assertThat(TenantContext.getCurrentTenant()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/verify-email", "/set-password", "/forgot-password", "/reset-password", "/user-images/pizza/a.png"})
    void hyphenatedPlatformRoutesAreNotTreatedAsUnknownRestaurants(String uri) throws Exception {
        var request = new MockHttpServletRequest("GET", uri);
        request.setServerName("localhost");
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> called.set(true));
        assertThat(called).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(TenantContextFilter.UNKNOWN_RESTAURANT)).isNull();
    }
}
