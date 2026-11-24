package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Coordinates owner-initiated location deletion (sandbox, or production locations that have never
 * issued a ticket or recorded a paid order) and permanent location closure (production locations
 * with fiscal history that must be retained for the statutory 4-year period).
 */
@Service
public class LocationLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(LocationLifecycleService.class);

    public enum RemovalAction {
        SANDBOX_DELETE,
        PRODUCTION_DELETE,
        PRODUCTION_CLOSE
    }

    public record DeleteResult(Outcome outcome, String customDomain) {
        public enum Outcome {
            DELETED,
            NAME_MISMATCH,
            HAS_TICKETS
        }
    }

    public record CloseResult(Outcome outcome, String customDomain) {
        public enum Outcome {
            CLOSED,
            NAME_MISMATCH,
            SANDBOX_USE_DELETE
        }
    }

    private final TenantLocationService locations;
    private final TenantDeletionPort deletionPort;
    private final BillingPort billing;
    private final SubscriptionService subscriptions;
    private final TenantBackupRestorePort backups;
    private final PlatformEnvironment environment;
    private final Clock clock;

    public LocationLifecycleService(
            TenantLocationService locations,
            TenantDeletionPort deletionPort,
            BillingPort billing,
            SubscriptionService subscriptions,
            TenantBackupRestorePort backups,
            PlatformEnvironment environment,
            Clock clock) {
        this.locations = Objects.requireNonNull(locations, "locations");
        this.deletionPort = Objects.requireNonNull(deletionPort, "deletionPort");
        this.billing = Objects.requireNonNull(billing, "billing");
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.backups = Objects.requireNonNull(backups, "backups");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.clock = Objects.requireNonNull(clock, "clock");
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

    public DeleteResult deleteLocation(String ownerUsername, String tenantId, String confirmationName) {
        requireOwner(ownerUsername, tenantId);
        requireNotClosed(tenantId);
        if (!matchesConfirmation(tenantId, confirmationName)) {
            return new DeleteResult(DeleteResult.Outcome.NAME_MISMATCH, null);
        }
        if (!environment.sandbox() && deletionPort.hasFiscalOrPaidActivity(tenantId)) {
            return new DeleteResult(DeleteResult.Outcome.HAS_TICKETS, null);
        }
        String customDomain = locations.getLocation(tenantId)
                .map(TenantLocation::customDomain)
                .orElse(null);
        if (!environment.sandbox()) {
            subscriptions.cancelSubscriptionImmediately(tenantId, ownerUsername);
        }
        deletionPort.deleteTenantCompletely(tenantId);
        log.info("Owner '{}' permanently deleted {} location '{}'",
                ownerUsername, environment.sandbox() ? "sandbox" : "production", tenantId);
        return new DeleteResult(DeleteResult.Outcome.DELETED, customDomain);
    }

    public CloseResult closeLocation(String ownerUsername, String tenantId, String confirmationName) {
        requireOwner(ownerUsername, tenantId);
        requireNotClosed(tenantId);
        if (environment.sandbox()) {
            return new CloseResult(CloseResult.Outcome.SANDBOX_USE_DELETE, null);
        }
        if (!matchesConfirmation(tenantId, confirmationName)) {
            return new CloseResult(CloseResult.Outcome.NAME_MISMATCH, null);
        }
        String customDomain = locations.getLocation(tenantId)
                .map(TenantLocation::customDomain)
                .orElse(null);
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
        log.info("Owner '{}' permanently closed production location '{}'", ownerUsername, tenantId);
        return new CloseResult(CloseResult.Outcome.CLOSED, customDomain);
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
