package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.application.platform.LinkSignature;
import es.brasatech.fastbite.application.platform.LinkedLocationService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.platform.SandboxLinkPort;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort;
import es.brasatech.fastbite.application.tenant.TenantDeletionPort;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantContextFilter;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.security.TenantAccessFilter;
import es.brasatech.fastbite.security.TenantUser;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class LocationAlignWebMvcTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private LinkController controller(String mode, boolean linked,
                                      TenantLocationService locations,
                                      TenantDeletionPort deletionPort,
                                      TenantRoutingResolver resolver) {
        var env = new PlatformEnvironment(mode);
        var link = linked
                ? new PlatformLink(env, "https://partner.example", "https://partner.example", SECRET, false)
                : new PlatformLink(env, "", "", "", mode.equals("production"));
        return new LinkController(env, link, mock(LinkedLocationService.class), mock(PasswordEncoder.class),
                JSON, "1.0.0", locations, mock(ProductService.class), mock(TenantBackupRestorePort.class),
                deletionPort, resolver, FIXED_CLOCK, "Europe/Madrid");
    }

    @Test
    void productionLocationStatusVerifiesSignatureVersionAndOwner() {
        var locations = mock(TenantLocationService.class);
        var deletion = mock(TenantDeletionPort.class);
        var ctrl = controller("production", true, locations, deletion, null);

        when(locations.getLocation("bistro")).thenReturn(Optional.of(new TenantLocation("1", "owner@example.com", "bistro", "PRO", null)));
        when(locations.isOwnerOf("owner@example.com", "bistro")).thenReturn(true);
        when(locations.isClosed("bistro")).thenReturn(false);
        when(deletion.hasFiscalOrPaidActivity("bistro")).thenReturn(true);
        when(deletion.hasOrdersInProgressToday(eq("bistro"), any(), any())).thenReturn(false);

        byte[] payload = LinkSignature.locationStatusPayload("owner@example.com", "bistro");
        String sig = LinkSignature.header(SECRET, NOW, payload);

        // Valid signed call
        var ok = ctrl.locationStatus("owner@example.com", "bistro", sig, "1.0.0");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = (SandboxLinkPort.ProductionLocationStatus) ok.getBody();
        assertThat(body).isEqualTo(new SandboxLinkPort.ProductionLocationStatus(true, false, true, false));

        // Forged / unsigned -> 401
        assertThat(ctrl.locationStatus("owner@example.com", "bistro", "bad-sig", "1.0.0").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // Version mismatch -> 409
        assertThat(ctrl.locationStatus("owner@example.com", "bistro", sig, "0.9.9").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        // Foreign owner -> 404
        byte[] foreignPayload = LinkSignature.locationStatusPayload("stranger@example.com", "bistro");
        String foreignSig = LinkSignature.header(SECRET, NOW, foreignPayload);
        assertThat(ctrl.locationStatus("stranger@example.com", "bistro", foreignSig, "1.0.0").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Production NEVER exposes delete-location over the link -> 404!
        byte[] delBody = "{\"tenantId\":\"bistro\",\"ownerUsername\":\"owner@example.com\"}".getBytes(StandardCharsets.UTF_8);
        String delSig = LinkSignature.header(SECRET, NOW, delBody);
        assertThat(ctrl.deleteLocation(delBody, delSig, "1.0.0").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(deletion, never()).deleteTenantCompletely(any());
    }

    @Test
    void sandboxDeleteEndpointEnforcesSignatureOwnerProtectedAndIdempotency() {
        var locations = mock(TenantLocationService.class);
        var deletion = mock(TenantDeletionPort.class);
        var resolver = mock(TenantRoutingResolver.class);
        var ctrl = controller("sandbox", true, locations, deletion, resolver);

        when(deletion.isProtectedTenant("kebab")).thenReturn(true);
        when(deletion.isProtectedTenant("bistro")).thenReturn(false);
        when(deletion.isProtectedTenant("gone")).thenReturn(false);

        when(locations.getLocation("bistro")).thenReturn(Optional.of(new TenantLocation("1", "owner@example.com", "bistro", "PRO", null)));
        when(locations.isOwnerOf("owner@example.com", "bistro")).thenReturn(true);
        when(locations.getLocation("gone")).thenReturn(Optional.empty());

        byte[] body = "{\"tenantId\":\"bistro\",\"ownerUsername\":\"owner@example.com\"}".getBytes(StandardCharsets.UTF_8);
        String sig = LinkSignature.header(SECRET, NOW, body);

        // Forged / unsigned -> 401
        assertThat(ctrl.deleteLocation(body, null, "1.0.0").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Foreign owner -> 404
        byte[] foreignBody = "{\"tenantId\":\"bistro\",\"ownerUsername\":\"other@example.com\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(ctrl.deleteLocation(foreignBody, LinkSignature.header(SECRET, NOW, foreignBody), "1.0.0").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Protected tenant -> 403
        byte[] protectedBody = "{\"tenantId\":\"kebab\",\"ownerUsername\":\"owner@example.com\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(ctrl.deleteLocation(protectedBody, LinkSignature.header(SECRET, NOW, protectedBody), "1.0.0").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Valid delete -> 200 & deletes
        assertThat(ctrl.deleteLocation(body, sig, "1.0.0").getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(deletion).deleteTenantCompletely("bistro");
        verify(resolver).evictTenant("bistro");

        // Idempotent when already gone -> 200
        byte[] goneBody = "{\"tenantId\":\"gone\",\"ownerUsername\":\"owner@example.com\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(ctrl.deleteLocation(goneBody, LinkSignature.header(SECRET, NOW, goneBody), "1.0.0").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void cleanEndingReturns404ForDeleted410ForClosedAndSignsOutStaffSessions() throws Exception {
        var locations = mock(TenantLocationService.class);
        when(locations.getLocation("deleted")).thenReturn(Optional.empty());
        when(locations.isClosed("deleted")).thenReturn(false);

        when(locations.getLocation("closedloc")).thenReturn(Optional.of(
                new TenantLocation("1", "owner@example.com", "closedloc", "PRO", null, null, false, false, NOW)));
        when(locations.isClosed("closedloc")).thenReturn(true);

        var resolver = new TenantRoutingResolver(locations);
        var filter = new TenantContextFilter(resolver);
        FilterChain chain = mock(FilterChain.class);

        // Deleted public menu -> 404 + unknownRestaurant attribute
        var reqDel = new MockHttpServletRequest("GET", "/deleted/menu");
        var resDel = new MockHttpServletResponse();
        filter.doFilter(reqDel, resDel, chain);
        assertThat(resDel.getStatus()).isEqualTo(404);
        assertThat(reqDel.getAttribute(TenantContextFilter.UNKNOWN_RESTAURANT)).isEqualTo(Boolean.TRUE);

        // Closed table QR -> 404 + unknownRestaurant attribute
        var reqClosed = new MockHttpServletRequest("GET", "/closedloc/menu");
        reqClosed.setQueryString("table=1");
        var resClosed = new MockHttpServletResponse();
        filter.doFilter(reqClosed, resClosed, chain);
        assertThat(resClosed.getStatus()).isEqualTo(404);
        assertThat(reqClosed.getAttribute(TenantContextFilter.UNKNOWN_RESTAURANT)).isEqualTo(Boolean.TRUE);

        // Online order API on closed location -> 404 JSON, no stack trace
        var reqApi = new MockHttpServletRequest("POST", "/closedloc/api/create-order");
        var resApi = new MockHttpServletResponse();
        filter.doFilter(reqApi, resApi, chain);
        assertThat(resApi.getStatus()).isEqualTo(404);
        assertThat(resApi.getContentAsString()).contains("restaurant_unavailable");

        // Staff session bound to deleted location -> invalidated + redirected to /login on next page request
        var staffUser = new TenantUser("cashier1", "pw", true,
                List.of(new SimpleGrantedAuthority("ROLE_CASHIER")), "deleted");
        var auth = UsernamePasswordAuthenticationToken.authenticated(staffUser, null, staffUser.getAuthorities());
        var sc = SecurityContextHolder.createEmptyContext();
        sc.setAuthentication(auth);

        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, sc);
        var staffPageReq = new MockHttpServletRequest("GET", "/deleted/counter");
        staffPageReq.setSession(session);
        var staffPageRes = new MockHttpServletResponse();
        filter.doFilter(staffPageReq, staffPageRes, chain);
        assertThat(session.isInvalid()).isTrue();
        assertThat(staffPageRes.getStatus()).isEqualTo(302);
        assertThat(staffPageRes.getRedirectedUrl()).isEqualTo("/login");

        // Staff session hitting API -> invalidated + 401
        var accessFilter = new TenantAccessFilter(locations, resolver);
        var apiSession = new MockHttpSession();
        SecurityContextHolder.setContext(sc);
        try {
            var staffApiReq = new MockHttpServletRequest("GET", "/closedloc/api/orders");
            staffApiReq.setSession(apiSession);
            var staffApiRes = new MockHttpServletResponse();
            accessFilter.doFilter(staffApiReq, staffApiRes, chain);
            assertThat(apiSession.isInvalid()).isTrue();
            assertThat(staffApiRes.getStatus()).isEqualTo(401);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void staffRequestDoesNotHitLocationPortWhenTenantIsCached() throws Exception {
        var locations = mock(TenantLocationService.class);
        when(locations.getLocation("bistro")).thenReturn(Optional.of(
                new TenantLocation("1", "owner@example.com", "bistro", "PRO", null)));
        when(locations.isClosed("bistro")).thenReturn(false);

        var resolver = new TenantRoutingResolver(locations);
        assertThat(resolver.exists("bistro")).isTrue();
        verify(locations, times(1)).getLocation("bistro");
        verify(locations, times(1)).isClosed("bistro");

        var accessFilter = new TenantAccessFilter(locations, resolver);
        var staffUser = new TenantUser("cook1", "pw", true,
                List.of(new SimpleGrantedAuthority("ROLE_COOK")), "bistro");
        var auth = UsernamePasswordAuthenticationToken.authenticated(staffUser, null, staffUser.getAuthorities());
        var sc = SecurityContextHolder.createEmptyContext();
        sc.setAuthentication(auth);
        FilterChain chain = mock(FilterChain.class);

        SecurityContextHolder.setContext(sc);
        try {
            for (int i = 0; i < 5; i++) {
                var req = new MockHttpServletRequest("GET", "/bistro/dashboard/api/orders");
                var res = new MockHttpServletResponse();
                accessFilter.doFilter(req, res, chain);
                assertThat(res.getStatus()).isEqualTo(200);
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
        // Zero additional DB lookups after tenant is cached in TenantRoutingResolver!
        verifyNoMoreInteractions(locations);
    }
}
