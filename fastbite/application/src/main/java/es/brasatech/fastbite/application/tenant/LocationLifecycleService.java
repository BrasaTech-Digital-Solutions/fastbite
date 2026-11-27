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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Coordinates owner-initiated location deletion (soft-delete with configurable undo window, then
 * scheduled permanent erase) and permanent location closure (production locations with fiscal
 * history that must be retained for the statutory 4-year period).
 * <p>
 * In linked mode, production is the authority: a linked sandbox queries production's read-only
 * status endpoint and never modifies production data over the link; after production hides,
 * restores, erases, or closes a location, it syncs the sandbox copy automatically (with retry on failure).
 */
@Service
public class LocationLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(LocationLifecycleService.class);

    public static final int DEFAULT_UNDO_DAYS = 7;
    public static final int DEFAULT_NAME_HOLD_DAYS = 90;
    static final Duration ERASE_CLAIM_LEASE = Duration.ofMinutes(15);

    public enum RemovalAction {
        SANDBOX_DELETE,
        PRODUCTION_DELETE,
        PRODUCTION_CLOSE
    }

    public record DeleteResult(Outcome outcome, String customDomain, String redirectUrl, Instant eraseAfter) {
        public DeleteResult(Outcome outcome, String customDomain) {
            this(outcome, customDomain, null, null);
        }

        public DeleteResult(Outcome outcome, String customDomain, String redirectUrl) {
            this(outcome, customDomain, redirectUrl, null);
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

    public record UndoResult(Outcome outcome, String customDomain, String redirectUrl) {
        public UndoResult(Outcome outcome, String customDomain) {
            this(outcome, customDomain, null);
        }

        public enum Outcome {
            RESTORED,
            NOT_PENDING_DELETE,
            UNDO_WINDOW_EXPIRED,
            PRODUCTION_UNREACHABLE,
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
    private final LocationNameHoldPort nameHolds;
    private final Clock clock;
    private final ZoneId ordersTimeZone;
    private final int undoDays;
    private final int nameHoldDays;

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
            @Autowired(required = false) LocationNameHoldPort nameHolds,
            Clock clock,
            @Value("${fastbite.orders.time-zone:Europe/Madrid}") String ordersTimeZone,
            @Value("${fastbite.locations.undo-days:7}") int undoDays,
            @Value("${fastbite.locations.name-hold-days:90}") int nameHoldDays) {
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
        this.nameHolds = nameHolds;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ordersTimeZone = ZoneId.of(ordersTimeZone == null || ordersTimeZone.isBlank() ? "Europe/Madrid" : ordersTimeZone);
        this.undoDays = Math.max(0, undoDays);
        this.nameHoldDays = Math.max(0, nameHoldDays);
    }

    public LocationLifecycleService(
            TenantLocationService locations,
            TenantDeletionPort deletionPort,
            BillingPort billing,
            SubscriptionService subscriptions,
            TenantBackupRestorePort backups,
            PlatformEnvironment environment,
            PlatformLink link,
            SandboxLinkPort sandboxLink,
            SandboxDeletionSyncService sandboxDeletionSync,
            LocationLifecycleMailPort mailPort,
            Clock clock,
            String ordersTimeZone) {
        this(locations, deletionPort, billing, subscriptions, backups, environment,
                link, sandboxLink, sandboxDeletionSync, mailPort, null, clock, ordersTimeZone,
                DEFAULT_UNDO_DAYS, DEFAULT_NAME_HOLD_DAYS);
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
                null, null, null, null, null, clock, "Europe/Madrid",
                DEFAULT_UNDO_DAYS, DEFAULT_NAME_HOLD_DAYS);
    }

    public int undoDays() {
        return undoDays;
    }

    public int nameHoldDays() {
        return nameHoldDays;
    }

    public String productionLocationsUrl() {
        if (link == null || link.url() == null) {
            return null;
        }
        return link.url() + "/owner/console?view=locations";
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
        Instant now = clock.instant();
        Instant eraseAfter = now.plus(undoDays, ChronoUnit.DAYS);
        // Prevent renewals/trial-end charges during the undo window without cancelling immediately;
        // remember prior cancel_at_period_end state so undo restores it verbatim.
        Boolean stripeWasCanceling = !environment.sandbox()
                ? subscriptions.pauseBillingForPendingDelete(tenantId, ownerUsername)
                : null;
        locations.markPendingDelete(tenantId, ownerUsername, now, eraseAfter, stripeWasCanceling);

        boolean linkedProd = isLinkedProduction();
        if (linkedProd && sandboxDeletionSync != null) {
            sandboxDeletionSync.hideNowOrEnqueue(tenantId, ownerUsername, eraseAfter);
        }

        LocationLifecycleMailPort.Scope scope = environment.sandbox()
                ? LocationLifecycleMailPort.Scope.SANDBOX
                : (linkedProd ? LocationLifecycleMailPort.Scope.BOTH : LocationLifecycleMailPort.Scope.PRODUCTION);
        sendNoticeQuietly(ownerEmail, tenantId, LocationLifecycleMailPort.Action.DELETED, scope, now, eraseAfter, language);

        log.info("Owner '{}' hid {} location '{}' for deletion (erase after {})",
                ownerUsername, environment.sandbox() ? "sandbox" : "production", tenantId, eraseAfter);
        return new DeleteResult(DeleteResult.Outcome.DELETED, customDomain, null, eraseAfter);
    }

    /**
     * Undoes a pending deletion within the undo window, restoring the location (and its sandbox
     * copy in linked production mode) and restoring the exact prior Stripe cancel-at-period-end state.
     * In linked sandbox mode, redirects to production when a production copy exists.
     */
    public UndoResult undoDelete(String ownerUsername, String tenantId) {
        requireOwner(ownerUsername, tenantId);

        if (isLinkedSandbox()) {
            SandboxLinkPort.ProductionLocationStatus prodStatus;
            try {
                prodStatus = queryProductionStatus(ownerUsername, tenantId);
            } catch (RuntimeException e) {
                log.warn("Could not reach production before undoing sandbox location '{}': {}", tenantId, e.getMessage());
                return new UndoResult(UndoResult.Outcome.PRODUCTION_UNREACHABLE, null, null);
            }
            if (prodStatus != null && prodStatus.exists()) {
                return new UndoResult(UndoResult.Outcome.REDIRECT_TO_PRODUCTION, null, productionLocationsUrl());
            }
        }

        TenantLocation loc = locations.getLocation(tenantId).orElse(null);
        if (loc == null || loc.isClosed() || !loc.isPendingDelete()) {
            return new UndoResult(UndoResult.Outcome.NOT_PENDING_DELETE, null);
        }
        Instant now = clock.instant();
        if (loc.eraseAfter() != null && !now.isBefore(loc.eraseAfter())) {
            return new UndoResult(UndoResult.Outcome.UNDO_WINDOW_EXPIRED, null);
        }

        if (!environment.sandbox()) {
            subscriptions.restoreBillingAfterUndo(tenantId, ownerUsername, loc.stripeWasCanceling());
        }
        locations.clearPendingDelete(tenantId);
        if (isLinkedProduction() && sandboxDeletionSync != null) {
            sandboxDeletionSync.restoreNowOrEnqueue(tenantId, ownerUsername);
        }

        log.info("Owner '{}' undid deletion of {} location '{}'",
                ownerUsername, environment.sandbox() ? "sandbox" : "production", tenantId);
        return new UndoResult(UndoResult.Outcome.RESTORED, loc.customDomain());
    }

    /**
     * Permanently erases locations whose undo window has elapsed and purges expired name holds.
     * Each due location is claimed atomically before erasing so concurrent instances cannot
     * double-erase or double-cancel Stripe. If a production location unexpectedly has fiscal or
     * paid activity at erase time, it is permanently closed instead (preserving records) and the
     * owner is emailed the close notice.
     */
    public int eraseDueLocations() {
        Instant now = clock.instant();
        Instant staleBefore = now.minus(ERASE_CLAIM_LEASE);
        List<TenantLocation> due = locations.findDueForErase(now);
        int erased = 0;
        for (TenantLocation loc : due) {
            String tenantId = loc.tenantId();
            if (loc.isClosed() || deletionPort.isProtectedTenant(tenantId)) {
                continue;
            }
            if (!locations.claimForErase(tenantId, now, staleBefore)) {
                continue;
            }
            try {
                if (!environment.sandbox()) {
                    if (deletionPort.hasFiscalOrPaidActivity(tenantId)) {
                        closeInsteadOfErase(loc, now);
                        erased++;
                        continue;
                    }
                    subscriptions.cancelSubscriptionImmediately(tenantId, loc.ownerUsername());
                    if (nameHolds != null && nameHoldDays > 0) {
                        nameHolds.hold(tenantId, loc.ownerUsername(), now.plus(nameHoldDays, ChronoUnit.DAYS), now);
                    }
                }
                deletionPort.deleteTenantCompletely(tenantId);
                if (isLinkedProduction() && sandboxDeletionSync != null) {
                    sandboxDeletionSync.deleteNowOrEnqueue(tenantId, loc.ownerUsername());
                }
                erased++;
                log.info("Permanently erased {} location '{}' after undo window expired",
                        environment.sandbox() ? "sandbox" : "production", tenantId);
            } catch (RuntimeException e) {
                log.warn("Could not permanently erase due location '{}': {}", tenantId, e.getMessage());
            }
        }
        if (nameHolds != null) {
            nameHolds.purgeExpired(now);
        }
        return erased;
    }

    private void closeInsteadOfErase(TenantLocation loc, Instant now) {
        String tenantId = loc.tenantId();
        String ownerUsername = loc.ownerUsername();
        String ownerEmail = resolveOwnerEmail(tenantId, ownerUsername);
        log.warn("Scheduled erase of production location '{}' found fiscal or paid activity; closing permanently instead to preserve records",
                tenantId);
        subscriptions.cancelSubscriptionAtPeriodEnd(tenantId, ownerUsername);
        backups.cleanTenantData(tenantId);
        billing.closeLocationData(tenantId, now);
        billing.updateLocked(tenantId, acc -> acc.withLifecycle(
                acc.pausedSince(),
                acc.pauseNotifiedAt(),
                acc.warningNotifiedAt(),
                acc.contractEndedAt(),
                acc.closingStartedAt(),
                now));
        locations.clearPendingDelete(tenantId);
        boolean linkedProd = isLinkedProduction();
        if (linkedProd && sandboxDeletionSync != null) {
            sandboxDeletionSync.deleteNowOrEnqueue(tenantId, ownerUsername);
        }
        LocationLifecycleMailPort.Scope scope = linkedProd
                ? LocationLifecycleMailPort.Scope.BOTH
                : LocationLifecycleMailPort.Scope.PRODUCTION;
        sendNoticeQuietly(ownerEmail, tenantId, LocationLifecycleMailPort.Action.CLOSED, scope, now, "es");
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
        sendNoticeQuietly(ownerEmail, tenantId, action, scope, when, null, language);
    }

    private void sendNoticeQuietly(
            String ownerEmail,
            String tenantId,
            LocationLifecycleMailPort.Action action,
            LocationLifecycleMailPort.Scope scope,
            Instant when,
            Instant undoUntil,
            String language) {
        if (mailPort == null || ownerEmail == null || !ownerEmail.contains("@")) {
            return;
        }
        try {
            if (undoUntil != null) {
                mailPort.sendLocationLifecycleNotice(ownerEmail, tenantId, action, scope, when, undoUntil, language);
            } else {
                mailPort.sendLocationLifecycleNotice(ownerEmail, tenantId, action, scope, when, language);
            }
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
