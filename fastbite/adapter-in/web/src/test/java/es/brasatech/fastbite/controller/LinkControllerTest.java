package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.platform.LinkSignature;
import es.brasatech.fastbite.application.platform.LinkedLocationService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.platform.SandboxLinkPort;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Production setting up its locations in the sandbox: only signed calls of the same version are taken. */
class LinkControllerTest {

    static final String SECRET = "a-secret-of-at-least-thirty-two-characters";
    static final byte[] BODY = "{\"tenantId\":\"anacafe\",\"ownerUsername\":\"ana\",\"ownerName\":\"Ana García\",\"plan\":\"RESTAURANT\"}"
            .getBytes(StandardCharsets.UTF_8);

    @WebMvcTest(controllers = LinkController.class)
    @ContextConfiguration(classes = {TestConfig.class, LinkController.class, PlatformEnvironment.class, PlatformLink.class,
            SecurityConfig.class, TenantRoutingResolver.class, WebConfig.class})
    abstract static class Endpoint {
        @Autowired MockMvc mockMvc;
        @MockitoBean LinkedLocationService locations;
        @MockitoBean TenantLocationService tenantLocationService;

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
    }
}
