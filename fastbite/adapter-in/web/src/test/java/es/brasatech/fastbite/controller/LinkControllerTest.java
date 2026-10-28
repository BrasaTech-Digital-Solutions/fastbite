package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.application.platform.LinkSignature;
import es.brasatech.fastbite.application.platform.LinkedLocationService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.platform.SandboxLinkPort;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.product.ProductDto;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Production setting up its locations in the sandbox: only signed calls of the same version are taken. */
class LinkControllerTest {

    static final String SECRET = "a-secret-of-at-least-thirty-two-characters";
    static final byte[] BODY = "{\"tenantId\":\"anacafe\",\"ownerUsername\":\"ana\",\"ownerName\":\"Ana García\",\"plan\":\"RESTAURANT\"}"
            .getBytes(StandardCharsets.UTF_8);
    static final byte[] BUNDLE_BODY = "{\"tenantId\":\"anacafe\",\"ownerUsername\":\"ana\"}".getBytes(StandardCharsets.UTF_8);

    @WebMvcTest(controllers = LinkController.class)
    @ContextConfiguration(classes = {TestConfig.class, LinkController.class, PlatformEnvironment.class, PlatformLink.class,
            SecurityConfig.class, TenantRoutingResolver.class, WebConfig.class})
    abstract static class Endpoint {
        @Autowired MockMvc mockMvc;
        @MockitoBean LinkedLocationService locations;
        @MockitoBean TenantLocationService tenantLocationService;
        @MockitoBean ProductService productService;
        @MockitoBean TenantBackupRestorePort backupRestorePort;

        @BeforeEach
        void setUp() {
            when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        }

        org.springframework.test.web.servlet.ResultActions call(String signature, String version) throws Exception {
            var request = post("/internal/link/locations").contentType(MediaType.APPLICATION_JSON).content(BODY);
            if (signature != null) request.header("X-FastBite-Signature", signature);
            if (version != null) request.header("X-FastBite-Version", version);
            return mockMvc.perform(request);
        }

        org.springframework.test.web.servlet.ResultActions callBundle(String signature, String version, byte[] body) throws Exception {
            var request = post("/internal/link/production-bundle")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body != null ? body : new byte[0]);
            if (signature != null) request.header("X-FastBite-Signature", signature);
            if (version != null) request.header("X-FastBite-Version", version);
            return mockMvc.perform(request);
        }
    }

    @Nested
    @TestPropertySource(properties = {"fastbite.environment=sandbox", "fastbite.link.url=http://production",
            "fastbite.link.secret=" + SECRET, "fastbite.version=1.2.0"})
    class LinkedSandbox extends Endpoint {

        @Test
        void aSignedCallOfTheSameVersionSetsTheLocationUp() throws Exception {
            call(LinkSignature.header(SECRET, Instant.now(), BODY), "1.2.0").andExpect(status().isOk());

            verify(locations).receive(eq(new SandboxLinkPort.LinkedLocation("anacafe", "ana", "Ana García", "RESTAURANT")),
                    anyString());
        }

        @Test
        void callsNotSignedWithTheSecretAreRefused() throws Exception {
            call(null, "1.2.0").andExpect(status().isUnauthorized());
            call(LinkSignature.header("another-secret-of-at-least-thirty-two-chars", Instant.now(), BODY), "1.2.0")
                    .andExpect(status().isUnauthorized());
            call(LinkSignature.header(SECRET, Instant.now().minusSeconds(3600), BODY), "1.2.0")
                    .andExpect(status().isUnauthorized());
            verify(locations, never()).receive(any(), any());
        }

        @Test
        void anotherVersionIsAskedToTryAgainLater() throws Exception {
            call(LinkSignature.header(SECRET, Instant.now(), BODY), "1.1.0").andExpect(status().isConflict());
            verify(locations, never()).receive(any(), any());
        }

        @Test
        void aLocationOfAnotherOwnerIsAConflict() throws Exception {
            doThrow(new IllegalArgumentException("Location anacafe belongs to another owner in the sandbox"))
                    .when(locations).receive(any(), any());

            call(LinkSignature.header(SECRET, Instant.now(), BODY), "1.2.0").andExpect(status().isConflict());
        }

        @Test
        void aLocationBeingSetUpConcurrentlyReturnsServiceUnavailableWithRetryAfter() throws Exception {
            doThrow(new es.brasatech.fastbite.domain.tenant.TenantProvisioningInProgressException("Restaurant setup is currently in progress. Please retry."))
                    .when(locations).receive(any(), any());

            call(LinkSignature.header(SECRET, Instant.now(), BODY), "1.2.0")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "5"))
                    .andExpect(jsonPath("$.status").value("in_progress"))
                    .andExpect(jsonPath("$.message").value("Location setup is already in progress"));
        }

        @Test
        void aSignedCallOfTheSameVersionStreamsTheBundle() throws Exception {
            when(tenantLocationService.getLocation("anacafe")).thenReturn(Optional.of(mock(TenantLocation.class)));
            when(tenantLocationService.isOwnerOf("ana", "anacafe")).thenReturn(true);
            when(productService.findAll()).thenReturn(List.of(mock(ProductDto.class)));

            callBundle(LinkSignature.header(SECRET, Instant.now(), BUNDLE_BODY), "1.2.0", BUNDLE_BODY)
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/zip"))
                    .andExpect(header().string("Content-Disposition", "attachment; filename=\"bundle-anacafe.zip\""));

            verify(backupRestorePort).exportProductionBundle(eq("anacafe"), any());
        }

        @Test
        void bundleCallNotSignedWithTheSecretIsRefused() throws Exception {
            callBundle(null, "1.2.0", BUNDLE_BODY).andExpect(status().isUnauthorized());
            callBundle(LinkSignature.header("another-secret-of-at-least-thirty-two-chars", Instant.now(), BUNDLE_BODY), "1.2.0", BUNDLE_BODY)
                    .andExpect(status().isUnauthorized());
            verify(backupRestorePort, never()).exportProductionBundle(any(), any());
        }

        @Test
        void bundleCallWithAnotherVersionIsAConflict() throws Exception {
            callBundle(LinkSignature.header(SECRET, Instant.now(), BUNDLE_BODY), "1.1.0", BUNDLE_BODY)
                    .andExpect(status().isConflict());
            verify(backupRestorePort, never()).exportProductionBundle(any(), any());
        }

        @Test
        void bundleCallWithMissingTenantIdIsBadRequest() throws Exception {
            byte[] body = "{\"ownerUsername\":\"ana\"}".getBytes(StandardCharsets.UTF_8);
            callBundle(LinkSignature.header(SECRET, Instant.now(), body), "1.2.0", body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("tenantId and ownerUsername are required"));
            verify(backupRestorePort, never()).exportProductionBundle(any(), any());
        }

        @Test
        void bundleCallWithMissingOwnerUsernameIsBadRequest() throws Exception {
            byte[] body = "{\"tenantId\":\"anacafe\"}".getBytes(StandardCharsets.UTF_8);
            callBundle(LinkSignature.header(SECRET, Instant.now(), body), "1.2.0", body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("tenantId and ownerUsername are required"));
            verify(backupRestorePort, never()).exportProductionBundle(any(), any());
        }

        @Test
        void signatureSignedForTenantACannotFetchTenantB() throws Exception {
            byte[] signedBody = "{\"tenantId\":\"anacafe\",\"ownerUsername\":\"ana\"}".getBytes(StandardCharsets.UTF_8);
            String sig = LinkSignature.header(SECRET, Instant.now(), signedBody);
            byte[] alteredBody = "{\"tenantId\":\"othercafe\",\"ownerUsername\":\"ana\"}".getBytes(StandardCharsets.UTF_8);

            callBundle(sig, "1.2.0", alteredBody).andExpect(status().isUnauthorized());
            verify(backupRestorePort, never()).exportProductionBundle(any(), any());
        }

        @Test
        void bundleCallForMissingLocationIsNotFound() throws Exception {
            when(tenantLocationService.getLocation("anacafe")).thenReturn(Optional.empty());

            callBundle(LinkSignature.header(SECRET, Instant.now(), BUNDLE_BODY), "1.2.0", BUNDLE_BODY)
                    .andExpect(status().isNotFound());
            verify(backupRestorePort, never()).exportProductionBundle(any(), any());
        }

        @Test
        void bundleCallByNonOwnerIsForbidden() throws Exception {
            when(tenantLocationService.getLocation("anacafe")).thenReturn(Optional.of(mock(TenantLocation.class)));
            when(tenantLocationService.isOwnerOf("ana", "anacafe")).thenReturn(false);

            callBundle(LinkSignature.header(SECRET, Instant.now(), BUNDLE_BODY), "1.2.0", BUNDLE_BODY)
                    .andExpect(status().isForbidden());
            verify(backupRestorePort, never()).exportProductionBundle(any(), any());
        }

        @Test
        void bundleCallWithNoProductsIsUnprocessableEntity() throws Exception {
            when(tenantLocationService.getLocation("anacafe")).thenReturn(Optional.of(mock(TenantLocation.class)));
            when(tenantLocationService.isOwnerOf("ana", "anacafe")).thenReturn(true);
            when(productService.findAll()).thenReturn(List.of());

            callBundle(LinkSignature.header(SECRET, Instant.now(), BUNDLE_BODY), "1.2.0", BUNDLE_BODY)
                    .andExpect(status().isUnprocessableEntity());
            verify(backupRestorePort, never()).exportProductionBundle(any(), any());
        }
    }

    @Nested
    @TestPropertySource(properties = {"fastbite.environment=production", "fastbite.link.url=http://sandbox",
            "fastbite.link.secret=" + SECRET, "fastbite.version=1.2.0"})
    class Production extends Endpoint {

        @Test
        void productionDoesNotTakeLocations() throws Exception {
            call(LinkSignature.header(SECRET, Instant.now(), BODY), "1.2.0").andExpect(status().isNotFound());
            verify(locations, never()).receive(any(), any());
        }

        @Test
        void productionDoesNotStreamBundles() throws Exception {
            callBundle(LinkSignature.header(SECRET, Instant.now(), BUNDLE_BODY), "1.2.0", BUNDLE_BODY)
                    .andExpect(status().isNotFound());
            verify(backupRestorePort, never()).exportProductionBundle(any(), any());
        }
    }
}
