package es.brasatech.fastbite.application.platform;

import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Keeps the linked sandbox aligned when production hides, restores, deletes, or closes a location.
 * Runs immediately after production's own operation succeeds, and retries via
 * {@link PendingSandboxDeletionPort} if the sandbox is temporarily unreachable.
 */
@Service
public class SandboxDeletionSyncService {

    private static final Logger log = LoggerFactory.getLogger(SandboxDeletionSyncService.class);

    static final Duration FIRST_RETRY = Duration.ofSeconds(30);
    static final Duration LONGEST_WAIT = Duration.ofMinutes(30);
    static final Duration CLAIM_LEASE = Duration.ofMinutes(15);
    private static final int BATCH_SIZE = 20;

    private final PlatformLink link;
    private final SandboxLinkPort sandboxLink;
    private final PendingSandboxDeletionPort pending;
    private final TenantLocationService locations;
    private final Clock clock;

    @Autowired
    public SandboxDeletionSyncService(
            PlatformLink link,
            SandboxLinkPort sandboxLink,
            PendingSandboxDeletionPort pending,
            @Autowired(required = false) TenantLocationService locations,
            Clock clock) {
        this.link = Objects.requireNonNull(link, "link");
        this.sandboxLink = Objects.requireNonNull(sandboxLink, "sandboxLink");
        this.pending = Objects.requireNonNull(pending, "pending");
        this.locations = locations;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SandboxDeletionSyncService(
            PlatformLink link,
            SandboxLinkPort sandboxLink,
            PendingSandboxDeletionPort pending,
            Clock clock) {
        this(link, sandboxLink, pending, null, clock);
    }

    /**
     * Hides the sandbox copy now; if the call fails, persists a HIDE retry entry.
     */
    public boolean hideNowOrEnqueue(String tenantId, String ownerUsername, Instant eraseAfter) {
        if (!link.linkedProduction()) {
            return false;
        }
        Instant now = clock.instant();
        try {
            sandboxLink.hideSandboxLocation(tenantId, ownerUsername, eraseAfter);
            pending.markDone(tenantId);
            log.info("Hidden sandbox copy of location '{}' for owner '{}'", tenantId, ownerUsername);
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not hide sandbox copy of location '{}' immediately; queued for retry: {}",
                    tenantId, e.getMessage());
            pending.enqueue(tenantId, ownerUsername, PendingSandboxDeletionPort.Action.HIDE,
                    e.getMessage(), now, now.plus(FIRST_RETRY));
            return false;
        }
    }

    /**
     * Restores the sandbox copy now; if the call fails, persists a RESTORE retry entry.
     */
    public boolean restoreNowOrEnqueue(String tenantId, String ownerUsername) {
        if (!link.linkedProduction()) {
            return false;
        }
        Instant now = clock.instant();
        try {
            sandboxLink.restoreSandboxLocation(tenantId, ownerUsername);
            pending.markDone(tenantId);
            log.info("Restored sandbox copy of location '{}' for owner '{}'", tenantId, ownerUsername);
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not restore sandbox copy of location '{}' immediately; queued for retry: {}",
                    tenantId, e.getMessage());
            pending.enqueue(tenantId, ownerUsername, PendingSandboxDeletionPort.Action.RESTORE,
                    e.getMessage(), now, now.plus(FIRST_RETRY));
            return false;
        }
    }

    /**
     * Deletes the sandbox copy now; if the call fails, persists an ERASE retry entry.
     *
     * @return {@code true} if the sandbox copy was deleted immediately, {@code false} if unlinked or queued for retry
     */
    public boolean deleteNowOrEnqueue(String tenantId, String ownerUsername) {
        if (!link.linkedProduction()) {
            return false;
        }
        Instant now = clock.instant();
        try {
            sandboxLink.deleteSandboxLocation(tenantId, ownerUsername);
            pending.markDone(tenantId);
            log.info("Removed sandbox copy of location '{}' for owner '{}'", tenantId, ownerUsername);
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not delete sandbox copy of location '{}' immediately; queued for retry: {}",
                    tenantId, e.getMessage());
            pending.enqueue(tenantId, ownerUsername, PendingSandboxDeletionPort.Action.ERASE,
                    e.getMessage(), now, now.plus(FIRST_RETRY));
            return false;
        }
    }

    /**
     * Retries due pending sandbox hide/restore/erase calls idempotently.
     *
     * @return number of sandbox sync actions completed in this run
     */
    public int sendDue() {
        if (!link.linkedProduction()) {
            return 0;
        }
        Instant now = clock.instant();
        List<PendingSandboxDeletionPort.PendingDeletion> due = pending.due(now, BATCH_SIZE);
        int completed = 0;
        for (PendingSandboxDeletionPort.PendingDeletion item : due) {
            if (!pending.claim(item.tenantId(), now, now.minus(CLAIM_LEASE))) {
                continue;
            }
            PendingSandboxDeletionPort.Action action = item.action() == null
                    ? PendingSandboxDeletionPort.Action.ERASE
                    : item.action();
            try {
                switch (action) {
                    case HIDE -> {
                        Instant eraseAfter = locations == null ? null
                                : locations.getLocation(item.tenantId()).map(TenantLocation::eraseAfter).orElse(null);
                        sandboxLink.hideSandboxLocation(item.tenantId(), item.ownerUsername(), eraseAfter);
                    }
                    case RESTORE -> sandboxLink.restoreSandboxLocation(item.tenantId(), item.ownerUsername());
                    case ERASE -> sandboxLink.deleteSandboxLocation(item.tenantId(), item.ownerUsername());
                }
                pending.markDone(item.tenantId());
                completed++;
                log.info("Retry succeeded: sandbox {} for location '{}' (owner '{}')",
                        action, item.tenantId(), item.ownerUsername());
            } catch (RuntimeException e) {
                int nextAttempts = item.attempts() + 1;
                Instant nextAt = now.plus(backoff(item.attempts()));
                pending.reschedule(item.tenantId(), nextAttempts, nextAt, e.getMessage(), now);
                log.warn("Retry {} failed for sandbox {} of location '{}'; next attempt at {}: {}",
                        nextAttempts, action, item.tenantId(), nextAt, e.getMessage());
            }
        }
        return completed;
    }

    static Duration backoff(int attemptsBefore) {
        int shift = Math.min(Math.max(attemptsBefore, 0), 10);
        Duration wait = FIRST_RETRY.multipliedBy(1L << shift);
        return wait.compareTo(LONGEST_WAIT) > 0 ? LONGEST_WAIT : wait;
    }
}
