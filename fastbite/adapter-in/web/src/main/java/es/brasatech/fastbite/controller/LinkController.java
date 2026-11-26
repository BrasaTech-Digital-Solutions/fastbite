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
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.domain.tenant.TenantProvisioningInProgressException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

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
    private final TenantLocationService tenantLocationService;
    private final ProductService productService;
    private final TenantBackupRestorePort backupRestorePort;
    private final TenantDeletionPort deletionPort;
    private final TenantRoutingResolver routingResolver;
    private final Clock clock;
    private final ZoneId ordersTimeZone;

    @Autowired
    public LinkController(PlatformEnvironment environment, PlatformLink link, LinkedLocationService locations,
            PasswordEncoder passwords, ObjectMapper json, @Value("${fastbite.version:dev}") String version,
            TenantLocationService tenantLocationService, ProductService productService,
            TenantBackupRestorePort backupRestorePort,
            @Autowired(required = false) TenantDeletionPort deletionPort,
            @Autowired(required = false) TenantRoutingResolver routingResolver,
            @Autowired(required = false) Clock clock,
            @Value("${fastbite.orders.time-zone:Europe/Madrid}") String ordersTimeZone) {
        this.environment = environment;
        this.link = link;
        this.locations = locations;
        this.passwords = passwords;
        this.json = json;
        this.version = version;
        this.tenantLocationService = tenantLocationService;
        this.productService = productService;
        this.backupRestorePort = backupRestorePort;
        this.deletionPort = deletionPort;
        this.routingResolver = routingResolver;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.ordersTimeZone = ZoneId.of(ordersTimeZone == null || ordersTimeZone.isBlank() ? "Europe/Madrid" : ordersTimeZone);
    }

    public LinkController(PlatformEnvironment environment, PlatformLink link, LinkedLocationService locations,
            PasswordEncoder passwords, ObjectMapper json, String version,
            TenantLocationService tenantLocationService, ProductService productService,
            TenantBackupRestorePort backupRestorePort) {
        this(environment, link, locations, passwords, json, version, tenantLocationService, productService,
                backupRestorePort, null, null, Clock.systemUTC(), "Europe/Madrid");
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
        } catch (TenantProvisioningInProgressException e) {
            log.info("Location {} setup is already in progress in the sandbox", location.tenantId());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "5")
                    .body(Map.of("status", "in_progress", "message", "Location setup is already in progress"));
        }
        log.info("Location {} of {} is set up in the sandbox", location.tenantId(), location.ownerUsername());
        return ResponseEntity.ok(Map.of("status", "ready"));
    }

    @PostMapping("/internal/link/production-bundle")
    public ResponseEntity<?> bundle(@RequestBody(required = false) byte[] content,
            @RequestHeader(value = "X-FastBite-Signature", required = false) String signature,
            @RequestHeader(value = "X-FastBite-Version", required = false) String senderVersion,
            HttpServletResponse response) throws IOException {
        if (!environment.sandbox() || !link.linked()) {
            return ResponseEntity.notFound().build();
        }
        if (content == null || content.length == 0 || !LinkSignature.verify(link.secret(), signature, content, Instant.now())) {
            log.warn("Refused a link call that is not signed with the shared secret, or too old");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Not signed by production"));
        }
        if (!version.equals(senderVersion)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message",
                    "The sandbox runs FastBite " + version + ", production " + senderVersion));
        }
        JsonNode body = json.readTree(content);
        String tenantId = text(body, "tenantId");
        String ownerUsername = text(body, "ownerUsername");
        if (tenantId == null || tenantId.isBlank() || ownerUsername == null || ownerUsername.isBlank()) {
            log.warn("Refused a link bundle call with missing or blank tenantId or ownerUsername");
            return ResponseEntity.badRequest().body(Map.of("message", "tenantId and ownerUsername are required"));
        }
        if (tenantLocationService.getLocation(tenantId).isEmpty()) {
            log.warn("Refused bundle export: location {} not found in the sandbox", tenantId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Location " + tenantId + " not found in the sandbox"));
        }
        if (!tenantLocationService.isOwnerOf(ownerUsername, tenantId)) {
            log.warn("Refused bundle export: user {} does not own location {} in the sandbox", ownerUsername, tenantId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "User " + ownerUsername + " does not own location " + tenantId));
        }
        String prev = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(tenantId);
        try {
            if (productService.findAll().isEmpty()) {
                log.info("Refused bundle export: location {} has no products to promote", tenantId);
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(Map.of("message", "Location " + tenantId + " has no products to promote"));
            }
        } finally {
            if (prev == null) TenantContext.clear();
            else TenantContext.setCurrentTenant(prev);
        }

        response.setStatus(HttpStatus.OK.value());
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bundle-" + tenantId + ".zip\"");
        backupRestorePort.exportProductionBundle(tenantId, response.getOutputStream());
        // Returning null tells Spring MVC that the response body was written directly
        // to HttpServletResponse.getOutputStream() (streaming chunked zip).
        return null;
    }

    /**
     * Production-only read-only status endpoint queried by a linked sandbox before deleting a location.
     * Production never exposes a delete endpoint over the link.
     */
    @GetMapping("/internal/link/location-status")
    public ResponseEntity<?> locationStatus(
            @RequestParam("owner") String owner,
            @RequestParam("tenant") String tenant,
            @RequestHeader(value = "X-FastBite-Signature", required = false) String signature,
            @RequestHeader(value = "X-FastBite-Version", required = false) String senderVersion) {
        if (!environment.production() || !link.linked()) {
            return ResponseEntity.notFound().build();
        }
        if (owner == null || owner.isBlank() || tenant == null || tenant.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "owner and tenant are required"));
        }
        byte[] payload = LinkSignature.locationStatusPayload(owner, tenant);
        if (!LinkSignature.verify(link.secret(), signature, payload, clock.instant())) {
            log.warn("Refused a location-status link call without a valid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Not signed by sandbox"));
        }
        if (!version.equals(senderVersion)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message",
                    "Production runs FastBite " + version + ", sandbox " + senderVersion));
        }
        Optional<TenantLocation> location = tenantLocationService.getLocation(tenant);
        if (location.isEmpty()) {
            return ResponseEntity.ok(SandboxLinkPort.ProductionLocationStatus.ABSENT);
        }
        if (!tenantLocationService.isOwnerOf(owner, tenant)) {
            return ResponseEntity.notFound().build();
        }
        boolean closed = tenantLocationService.isClosed(tenant);
        boolean hasActivity = closed || (deletionPort != null && deletionPort.hasFiscalOrPaidActivity(tenant));
        LocalDateTime todayStart = LocalDate.now(clock.withZone(ordersTimeZone)).atStartOfDay();
        boolean hasOrdersInProgress = !closed
                && deletionPort != null
                && deletionPort.hasOrdersInProgressToday(tenant, todayStart, todayStart.plusDays(1));
        return ResponseEntity.ok(new SandboxLinkPort.ProductionLocationStatus(
                true, closed, hasActivity, hasOrdersInProgress));
    }

    /**
     * Sandbox-only cleanup endpoint called by production after production deleted or closed a location.
     * Link-signed only, owner + location must match, refuses protected tenants, idempotent when already gone.
     */
    @PostMapping("/internal/link/delete-location")
    public ResponseEntity<Map<String, String>> deleteLocation(@RequestBody(required = false) byte[] content,
            @RequestHeader(value = "X-FastBite-Signature", required = false) String signature,
            @RequestHeader(value = "X-FastBite-Version", required = false) String senderVersion) {
        if (!environment.sandbox() || !link.linked()) {
            return ResponseEntity.notFound().build();
        }
        if (content == null || content.length == 0 || !LinkSignature.verify(link.secret(), signature, content, clock.instant())) {
            log.warn("Refused a delete-location link call that is not signed with the shared secret, or too old");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Not signed by production"));
        }
        if (!version.equals(senderVersion)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message",
                    "The sandbox runs FastBite " + version + ", production " + senderVersion));
        }
        JsonNode body = json.readTree(content);
        String tenantId = text(body, "tenantId");
        String ownerUsername = text(body, "ownerUsername");
        if (tenantId == null || tenantId.isBlank() || ownerUsername == null || ownerUsername.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "tenantId and ownerUsername are required"));
        }
        if (deletionPort == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "Sandbox deletion is not available"));
        }
        if (deletionPort.isProtectedTenant(tenantId)) {
            log.warn("Refused link delete for protected tenant {}", tenantId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Protected tenant cannot be deleted"));
        }
        Optional<TenantLocation> existing = tenantLocationService.getLocation(tenantId);
        if (existing.isEmpty()) {
            log.info("Sandbox location {} already absent during linked delete for owner {}", tenantId, ownerUsername);
            return ResponseEntity.ok(Map.of("status", "already_deleted"));
        }
        if (!tenantLocationService.isOwnerOf(ownerUsername, tenantId)) {
            log.warn("Refused link delete: owner {} does not own sandbox location {}", ownerUsername, tenantId);
            return ResponseEntity.notFound().build();
        }
        String customDomain = existing.get().customDomain();
        deletionPort.deleteTenantCompletely(tenantId);
        if (routingResolver != null) {
            routingResolver.evictTenant(tenantId);
            if (customDomain != null && !customDomain.isBlank()) {
                routingResolver.evictCache(customDomain);
            }
        }
        log.info("Deleted sandbox location {} for owner {} via production link", tenantId, ownerUsername);
        return ResponseEntity.ok(Map.of("status", "deleted"));
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
