package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.digitalsign.DigitalSignProperties;
import es.brasatech.fastbite.application.digitalsign.DigitalSignWebhookVerifier;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationService;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationService.WebhookOutcome;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DigitalSignWebhookController.class)
@ContextConfiguration(classes = {
        TestConfig.class,
        DigitalSignWebhookController.class,
        DigitalSignWebhookControllerTest.WebhookTestConfig.class,
        PlatformEnvironment.class,
        SecurityConfig.class,
        TenantRoutingResolver.class,
        WebConfig.class
})
class DigitalSignWebhookControllerTest {

    private static final String SECRET = "whsec_test_verifactu_secret_123456";
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    @Configuration
    static class WebhookTestConfig {
        @Bean
        DigitalSignWebhookVerifier digitalSignWebhookVerifier() {
            DigitalSignProperties props = new DigitalSignProperties();
            props.setWebhookSecret(SECRET);
            return new DigitalSignWebhookVerifier(props, Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VerifactuAuthorizationService authorizationService;

    @MockitoBean
    private TenantLocationService tenantLocationService;

    @BeforeEach
    void setUp() {
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void rejectsMissingInvalidOrExpiredSignatureWith401() throws Exception {
        byte[] body = """
                {"id":"evt_1","type":"request.completed","data":{"requestId":"req-1"}}
                """.strip().getBytes(StandardCharsets.UTF_8);

        // Missing signature header
        mockMvc.perform(post("/api/webhooks/digital-sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        // Wrong HMAC
        mockMvc.perform(post("/api/webhooks/digital-sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DigitalSignWebhookVerifier.SIGNATURE_HEADER, "t=" + NOW.getEpochSecond() + ",v1=deadbeef")
                        .header(DigitalSignWebhookVerifier.EVENT_ID_HEADER, "evt_1")
                        .content(body))
                .andExpect(status().isUnauthorized());

        // Expired timestamp (> 5 minutes old)
        long expiredTs = NOW.minusSeconds(301).getEpochSecond();
        String expiredSig = DigitalSignWebhookVerifier.signHeader(SECRET, expiredTs, body);
        mockMvc.perform(post("/api/webhooks/digital-sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DigitalSignWebhookVerifier.SIGNATURE_HEADER, expiredSig)
                        .header(DigitalSignWebhookVerifier.EVENT_ID_HEADER, "evt_1")
                        .content(body))
                .andExpect(status().isUnauthorized());

        verify(authorizationService, never()).handleWebhook(any(), any(), any());
    }

    @Test
    void processesValidSignedWebhookAndHandlesDuplicateWith200() throws Exception {
        byte[] body = """
                {"id":"evt_42","type":"request.completed","data":{"requestId":"req-42","status":"COMPLETED"}}
                """.strip().getBytes(StandardCharsets.UTF_8);
        String sig = DigitalSignWebhookVerifier.signHeader(SECRET, NOW.getEpochSecond(), body);

        when(authorizationService.handleWebhook("evt_42", "request.completed", "req-42"))
                .thenReturn(WebhookOutcome.PROCESSED)
                .thenReturn(WebhookOutcome.DUPLICATE);

        mockMvc.perform(post("/api/webhooks/digital-sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DigitalSignWebhookVerifier.SIGNATURE_HEADER, sig)
                        .header(DigitalSignWebhookVerifier.EVENT_ID_HEADER, "evt_42")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));

        mockMvc.perform(post("/api/webhooks/digital-sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DigitalSignWebhookVerifier.SIGNATURE_HEADER, sig)
                        .header(DigitalSignWebhookVerifier.EVENT_ID_HEADER, "evt_42")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().string("Duplicate"));

        verify(authorizationService, times(2))
                .handleWebhook("evt_42", "request.completed", "req-42");
    }

    @Test
    void verifiesHmacAgainstExactRawBytesWithNonCanonicalWhitespaceAndKeyOrder() throws Exception {
        byte[] rawBody = ("{\n  \"data\" : { \"status\" : \"COMPLETED\",   \"requestId\" : \"req-raw\" },\n"
                + "  \"type\" : \"request.completed\",\n"
                + "  \"id\" : \"evt_raw\"\n}").getBytes(StandardCharsets.UTF_8);
        String sig = DigitalSignWebhookVerifier.signHeader(SECRET, NOW.getEpochSecond(), rawBody);

        when(authorizationService.handleWebhook("evt_raw", "request.completed", "req-raw"))
                .thenReturn(WebhookOutcome.PROCESSED);

        mockMvc.perform(post("/api/webhooks/digital-sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(DigitalSignWebhookVerifier.SIGNATURE_HEADER, sig)
                        .header(DigitalSignWebhookVerifier.EVENT_ID_HEADER, "evt_raw")
                        .content(rawBody))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));

        verify(authorizationService).handleWebhook("evt_raw", "request.completed", "req-raw");
    }
}
