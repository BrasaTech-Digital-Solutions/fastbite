package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.digitalsign.DigitalSignWebhookVerifier;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationService;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationService.WebhookOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Receives Digital Sign webhooks ({@code POST /api/webhooks/digital-sign}).
 *
 * <p>Verifies {@code DigitalSign-Signature} on the raw request body bytes, deduplicates by
 * {@code DigitalSign-Event-Id}, and synchronously refreshes the request + downloads both PDFs
 * within a ~8 s budget (Cloud Run allocates CPU only during requests) before returning {@code 200 OK}.
 */
@RestController
@Slf4j
public class DigitalSignWebhookController {

    private final DigitalSignWebhookVerifier webhookVerifier;
    private final VerifactuAuthorizationService authorizationService;
    private final ObjectMapper objectMapper;

    @Autowired
    public DigitalSignWebhookController(
            DigitalSignWebhookVerifier webhookVerifier,
            VerifactuAuthorizationService authorizationService,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        this(webhookVerifier, authorizationService, objectMapperProvider.getIfAvailable(ObjectMapper::new));
    }

    DigitalSignWebhookController(
            DigitalSignWebhookVerifier webhookVerifier,
            VerifactuAuthorizationService authorizationService,
            ObjectMapper objectMapper) {
        this.webhookVerifier = webhookVerifier;
        this.authorizationService = authorizationService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @PostMapping("/api/webhooks/digital-sign")
    public ResponseEntity<String> handleWebhook(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = DigitalSignWebhookVerifier.SIGNATURE_HEADER, required = false) String signatureHeader,
            @RequestHeader(value = DigitalSignWebhookVerifier.EVENT_ID_HEADER, required = false) String eventIdHeader) {

        byte[] payload = rawBody == null ? new byte[0] : rawBody;
        if (!webhookVerifier.verify(signatureHeader, payload)) {
            log.warn("Rejected Digital Sign webhook with missing, invalid, or expired signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("Rejected Digital Sign webhook with malformed JSON payload");
            return ResponseEntity.badRequest().body("Invalid JSON");
        }
        if (root == null || !root.isObject()) {
            return ResponseEntity.badRequest().body("Invalid JSON");
        }

        String eventId = firstNonBlank(eventIdHeader, textOrNull(root, "id"));
        String eventType = firstNonBlank(textOrNull(root, "type"), "unknown");
        JsonNode dataNode = root.get("data");
        String requestId = dataNode != null && dataNode.isObject() ? textOrNull(dataNode, "requestId") : null;

        WebhookOutcome outcome = authorizationService.handleWebhook(eventId, eventType, requestId);
        return ResponseEntity.ok(outcome == WebhookOutcome.DUPLICATE ? "Duplicate" : "OK");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.isMissingNode() ? null : value.asString();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.strip();
        }
        return second != null && !second.isBlank() ? second.strip() : null;
    }
}
