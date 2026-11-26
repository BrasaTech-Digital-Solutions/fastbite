package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.application.mail.LocationLifecycleMailPort;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.platform.SandboxDeletionSyncService;
import es.brasatech.fastbite.application.platform.SandboxLinkPort;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Coordinates owner-initiated location deletion (sandbox, or production locations that have never
 * issued a ticket or recorded a paid order) and permanent location closure (production locations
 * with fiscal history that must be retained for the statutory 4-year period).
 * <p>
 * In linked mode, production is the authority: a linked sandbox queries production's read-only
 * status endpoint and never deletes production data over the link; after production deletes or
 * closes a location, it removes the sandbox copy automatically (with retry on failure).
 */
@Service
public class LocationLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(LocationLifecycleService.class);

    public enum RemovalAction {
        SANDBOX_DELETE,
        PRODUCTION_DELETE,
        PRODUCTION_CLOSE
    }

    public record DeleteResult(Outcome outcome, String customDomain, String redirectUrl) {
        public DeleteResult(Outcome outcome, String customDomain) {
            this(outcome, customDomain, null);
        }

        public enum Outcome {
            DELETED,
            NAME_MISMATCH,
            HAS_TICKETS,
            ORDERS_IN_PROGRESS,
            PRODUCTION_UNREACHABLE,
            PRODUCTION_HAS_TICKETS,
            REDIRECT_TO_PRODUCTION
        }
    }

    public record CloseResult(Outcome outcome, String customDomain) {
        public enum Outcome {
            CLOSED,
            NAME_MISMATCH,
            SANDBOX_USE_DELETE,
            ORDERS_IN_PROGRESS
        }
    }

    private final TenantLocationService locations;
    private final TenantDeletionPort deletionPort;
    private final BillingPort billing;
    private final SubscriptionService subscriptions;
    private final TenantBackupRestorePort backups;
    private final PlatformEnvironment environment;
    private final PlatformLink link;
    private final SandboxLinkPort sandboxLink;
    private final SandboxDeletionSyncService sandboxDeletionSync;
    private final LocationLifecycleMailPort mailPort;
    private final Clock clock;
    private final ZoneId ordersTimeZone;

    @Autowired
    public LocationLifecycleService(
            TenantLocationService locations,
            TenantDeletionPort deletionPort,
            BillingPort billing,
            SubscriptionService subscriptions,
            TenantBackupRestorePort backups,
            PlatformEnvironment environment,
            @Autowired(required = false) PlatformLink link,
            @Autowired(required = false) SandboxLinkPort sandboxLink,
            @Autowired(required = false) SandboxDeletionSyncService sandboxDeletionSync,
            @Autowired(required = false) LocationLifecycleMailPort mailPort,
            Clock clock,
            @Value("${fastbite.orders.time-zone:Europe/Madrid}") String ordersTimeZone) {
        this.locations = Objects.requireNonNull(locations, "locations");
        this.deletionPort = Objects.requireNonNull(deletionPort, "deletionPort");
        this.billing = Objects.requireNonNull(billing, "billing");
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.backups = Objects.requireNonNull(backups, "backups");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.link = link;
        this.sandboxLink = sandboxLink;
        this.sandboxDeletionSync = sandboxDeletionSync;
        this.mailPort = mailPort;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ordersTimeZone = ZoneId.of(ordersTimeZone == null || ordersTimeZone.isBlank() ? "Europe/Madrid" : ordersTimeZone);
    }

    public LocationLifecycleService(
            TenantLocationService locations,
            TenantDeletionPort deletionPort,
            BillingPort billing,
            SubscriptionService subscriptions,
            TenantBackupRestorePort backups,
            PlatformEnvironment environment,
            Clock clock) {
        this(locations, deletionPort, billing, subscriptions, backups, environment,
                null, null, null, null, clock, "Europe/Madrid");
    }

    public RemovalAction removalAction(String ownerUsername, String tenantId) {
        requireOwner(ownerUsername, tenantId);
        if (environment.sandbox()) {
            return RemovalAction.SANDBOX_DELETE;
        }
        return deletionPort.hasFiscalOrPaidActivity(tenantId)
                ? RemovalAction.PRODUCTION_CLOSE
                : RemovalAction.PRODUCTION_DELETE;
    }

    /**
     * Queries production's read-only status when running in linked sandbox mode.
     * Returns {@code null} when not in linked sandbox mode.
     */
    public SandboxLinkPort.ProductionLocationStatus queryProductionStatus(String ownerUsername, String tenantId) {
        if (!isLinkedSandbox()) {
            return null;
        }
        requireOwner(ownerUsername, tenantId);
        if (sandboxLink == null) {
            throw new SandboxLinkPort.Unavailable(SandboxLinkPort.Reason.UNREACHABLE, "Link client is not configured");
        }
        return sandboxLink.fetchProductionLocationStatus(tenantId, ownerUsername);
    }

    public String productionGeneralSettingsUrl(String tenantId) {
        if (link == null || link.url() == null) {
            return null;
        }
        String encoded = URLEncoder.encode(tenantId, StandardCharsets.UTF_8);
        return link.url() + "/owner/console?view=location-settings&section=general&location=" + encoded;
    }

    public String productionConfirmDeleteUrl(String tenantId) {
        String base = productionGeneralSettingsUrl(tenantId);
        return base == null ? null : base + "&confirmDelete=1";
    }

    public DeleteResult deleteLocation(String ownerUsername, String tenantId, String confirmationName) {
        String lang = LocaleContextHolder.getLocale().getLanguage();
        return deleteLocation(ownerUsername, tenantId, confirmationName, lang == null || lang.isBlank() ? "es" : lang);
    }

    public DeleteResult deleteLocation(String ownerUsername, String tenantId, String confirmationName, String language) {
        requireOwner(ownerUsername, tenantId);
        requireNotClosed(tenantId);

        if (isLinkedSandbox()) {
            SandboxLinkPort.ProductionLocationStatus prodStatus;
            try {
                prodStatus = queryProductionStatus(ownerUsername, tenantId);
            } catch (RuntimeException e) {
                log.warn("Could not reach production before deleting sandbox location '{}': {}", tenantId, e.getMessage());
                return new DeleteResult(DeleteResult.Outcome.PRODUCTION_UNREACHABLE, null, null);
            }
            if (prodStatus != null && prodStatus.exists()) {
                if (prodStatus.hasFiscalOrPaidActivity() || prodStatus.closed()) {
                    return new DeleteResult(
                            DeleteResult.Outcome.PRODUCTION_HAS_TICKETS,
                            null,
                            productionGeneralSettingsUrl(tenantId));
                }
                return new DeleteResult(
                        DeleteResult.Outcome.REDIRECT_TO_PRODUCTION,
                        null,
                        productionConfirmDeleteUrl(tenantId));
            }
        }

        if (!matchesConfirmation(tenantId, confirmationName)) {
            return new DeleteResult(DeleteResult.Outcome.NAME_MISMATCH, null);
        }
        if (hasOrdersInProgressToday(tenantId)) {
            return new DeleteResult(DeleteResult.Outcome.ORDERS_IN_PROGRESS, null);
        }
        if (!environment.sandbox() && deletionPort.hasFiscalOrPaidActivity(tenantId)) {
            return new DeleteResult(DeleteResult.Outcome.HAS_TICKETS, null);
        }
        String customDomain = locations.getLocation(tenantId)
                .map(TenantLocation::customDomain)
                .orElse(null);
        String ownerEmail = resolveOwnerEmail(tenantId, ownerUsername);
        if (!environment.sandbox()) {
            subscriptions.cancelSubscriptionImmediately(tenantId, ownerUsername);
        }
        deletionPort.deleteTenantCompletely(tenantId);

        boolean linkedProd = isLinkedProduction();
        if (linkedProd && sandboxDeletionSync != null) {
            sandboxDeletionSync.deleteNowOrEnqueue(tenantId, ownerUsername);
        }

        Instant now = clock.instant();
        LocationLifecycleMailPort.Scope scope = environment.sandbox()
                ? LocationLifecycleMailPort.Scope.SANDBOX
                : (linkedProd ? LocationLifecycleMailPort.Scope.BOTH : LocationLifecycleMailPort.Scope.PRODUCTION);
        sendNoticeQuietly(ownerEmail, tenantId, LocationLifecycleMailPort.Action.DELETED, scope, now, language);

        log.info("Owner '{}' permanently deleted {} location '{}'",
                ownerUsername, environment.sandbox() ? "sandbox" : "production", tenantId);
        return new DeleteResult(DeleteResult.Outcome.DELETED, customDomain);
    }

    public CloseResult closeLocation(String ownerUsername, String tenantId, String confirmationName) {
        String lang = LocaleContextHolder.getLocale().getLanguage();
        return closeLocation(ownerUsername, tenantId, confirmationName, lang == null || lang.isBlank() ? "es" : lang);
    }

    public CloseResult closeLocation(String ownerUsername, String tenantId, String confirmationName, String language) {
        requireOwner(ownerUsername, tenantId);
        requireNotClosed(tenantId);
        if (environment.sandbox()) {
            return new CloseResult(CloseResult.Outcome.SANDBOX_USE_DELETE, null);
        }
        if (!matchesConfirmation(tenantId, confirmationName)) {
            return new CloseResult(CloseResult.Outcome.NAME_MISMATCH, null);
        }
        if (hasOrdersInProgressToday(tenantId)) {
            return new CloseResult(CloseResult.Outcome.ORDERS_IN_PROGRESS, null);
        }
        String customDomain = locations.getLocation(tenantId)
                .map(TenantLocation::customDomain)
                .orElse(null);
        String ownerEmail = resolveOwnerEmail(tenantId, ownerUsername);
        subscriptions.cancelSubscriptionAtPeriodEnd(tenantId, ownerUsername);
        Instant now = clock.instant();
        backups.cleanTenantData(tenantId);
        billing.closeLocationData(tenantId, now);
        billing.updateLocked(tenantId, acc -> acc.withLifecycle(
                acc.pausedSince(),
                acc.pauseNotifiedAt(),
                acc.warningNotifiedAt(),
                acc.contractEndedAt(),
                acc.closingStartedAt(),
                now));

        boolean linkedProd = isLinkedProduction();
        if (linkedProd && sandboxDeletionSync != null) {
            sandboxDeletionSync.deleteNowOrEnqueue(tenantId, ownerUsername);
        }

        LocationLifecycleMailPort.Scope scope = linkedProd
                ? LocationLifecycleMailPort.Scope.BOTH
                : LocationLifecycleMailPort.Scope.PRODUCTION;
        sendNoticeQuietly(ownerEmail, tenantId, LocationLifecycleMailPort.Action.CLOSED, scope, now, language);

        log.info("Owner '{}' permanently closed production location '{}'", ownerUsername, tenantId);
        return new CloseResult(CloseResult.Outcome.CLOSED, customDomain);
    }

    public boolean hasOrdersInProgressToday(String tenantId) {
        LocalDateTime todayStart = LocalDate.now(clock.withZone(ordersTimeZone)).atStartOfDay();
        return deletionPort.hasOrdersInProgressToday(tenantId, todayStart, todayStart.plusDays(1));
    }

    private boolean isLinkedSandbox() {
        return link != null && link.linkedSandbox();
    }

    private boolean isLinkedProduction() {
        return link != null && link.linkedProduction();
    }

    private String resolveOwnerEmail(String tenantId, String ownerUsername) {
        if (subscriptions != null) {
            var resolved = subscriptions.resolveOwnerEmail(tenantId, ownerUsername);
            if (resolved != null && resolved.isPresent()) {
                return resolved.get();
            }
        }
        return ownerUsername != null && ownerUsername.contains("@") ? ownerUsername : null;
    }

    private void sendNoticeQuietly(
            String ownerEmail,
            String tenantId,
            LocationLifecycleMailPort.Action action,
            LocationLifecycleMailPort.Scope scope,
            Instant when,
            String language) {
        if (mailPort == null || ownerEmail == null || !ownerEmail.contains("@")) {
            return;
        }
        try {
            mailPort.sendLocationLifecycleNotice(ownerEmail, tenantId, action, scope, when, language);
        } catch (RuntimeException e) {
            log.warn("Could not send {} confirmation email to '{}' for location '{}': {}",
                    action, ownerEmail, tenantId, e.getMessage());
        }
    }

    private void requireOwner(String ownerUsername, String tenantId) {
        if (ownerUsername == null || tenantId == null || !locations.isOwnerOf(ownerUsername, tenantId)) {
            throw new IllegalArgumentException("You do not own this location.");
        }
    }

    private void requireNotClosed(String tenantId) {
        if (locations.isClosed(tenantId) || subscriptions.isClosed(tenantId)) {
            throw new IllegalStateException("This location is already closed.");
        }
    }

    private static boolean matchesConfirmation(String tenantId, String confirmationName) {
        return confirmationName != null
                && !confirmationName.isBlank()
                && tenantId.equalsIgnoreCase(confirmationName.strip());
    }
}
