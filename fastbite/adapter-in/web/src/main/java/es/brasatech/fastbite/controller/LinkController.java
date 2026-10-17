package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.platform.LinkSignature;
import es.brasatech.fastbite.application.platform.LinkedLocationService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.platform.SandboxLinkPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * The sandbox's side of the link: production sets up here every location it creates. Only a linked
 * sandbox answers, and only calls signed with the shared secret by the same version of FastBite.
 */
@RestController
@Slf4j
public class LinkController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlatformEnvironment environment;
    private final PlatformLink link;
    private final LinkedLocationService locations;
    private final PasswordEncoder passwords;
    private final ObjectMapper json;
    private final String version;

    public LinkController(PlatformEnvironment environment, PlatformLink link, LinkedLocationService locations,
            PasswordEncoder passwords, ObjectMapper json, @Value("${fastbite.version:dev}") String version) {
        this.environment = environment;
        this.link = link;
        this.locations = locations;
        this.passwords = passwords;
        this.json = json;
        this.version = version;
    }

    @PostMapping("/internal/link/locations")
    public ResponseEntity<Map<String, String>> location(@RequestBody byte[] content,
            @RequestHeader(value = "X-FastBite-Signature", required = false) String signature,
            @RequestHeader(value = "X-FastBite-Version", required = false) String senderVersion) {
        if (!environment.sandbox() || !link.linked()) {
            return ResponseEntity.notFound().build();
        }
        if (!LinkSignature.verify(link.secret(), signature, content, Instant.now())) {
            log.warn("Refused a link call that is not signed with the shared secret, or too old");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Not signed by production"));
        }
        if (!version.equals(senderVersion)) {
            // During a release: production tries again once both run the same version
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message",
                    "The sandbox runs FastBite " + version + ", production " + senderVersion));
        }
        JsonNode body = json.readTree(content);
        var location = new SandboxLinkPort.LinkedLocation(text(body, "tenantId"), text(body, "ownerUsername"),
                text(body, "ownerName"), text(body, "plan"));
        try {
            locations.receive(location, unusablePassword());
        } catch (IllegalArgumentException e) {
            log.warn("Refused to set up location {} in the sandbox: {}", location.tenantId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        } catch (es.brasatech.fastbite.domain.tenant.TenantProvisioningInProgressException e) {
            log.info("Location {} setup is already in progress in the sandbox", location.tenantId());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "5")
                    .body(Map.of("status", "in_progress", "message", "Location setup is already in progress"));
        }
        log.info("Location {} of {} is set up in the sandbox", location.tenantId(), location.ownerUsername());
        return ResponseEntity.ok(Map.of("status", "ready"));
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    /** Nobody knows it: a linked sandbox is entered through production, never with a password. */
    private String unusablePassword() {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return passwords.encode(Base64.getEncoder().encodeToString(random));
    }
}
