package es.brasatech.fastbite.application.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Keeps the linked sandbox aligned when production deletes or closes a location.
 * Runs immediately after production's own operation succeeds, and retries via
 * {@link PendingSandboxDeletionPort} if the sandbox is temporarily unreachable.
 */
@Service
public class SandboxDeletionSyncService {

    private static final Logger log = LoggerFactory.getLogger(SandboxDeletionSyncService.class);

    static final Duration FIRST_RETRY = Duration.ofSeconds(30);
    static final Duration LONGEST_WAIT = Duration.ofMinutes(30);
    private static final int BATCH_SIZE = 20;

    private final PlatformLink link;
    private final SandboxLinkPort sandboxLink;
    private final PendingSandboxDeletionPort pending;
    private final Clock clock;

    public SandboxDeletionSyncService(
            PlatformLink link,
            SandboxLinkPort sandboxLink,
            PendingSandboxDeletionPort pending,
            Clock clock) {
        this.link = Objects.requireNonNull(link, "link");
        this.sandboxLink = Objects.requireNonNull(sandboxLink, "sandboxLink");
        this.pending = Objects.requireNonNull(pending, "pending");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Deletes the sandbox copy now; if the call fails, persists a retry entry.
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
            pending.enqueue(tenantId, ownerUsername, e.getMessage(), now, now.plus(FIRST_RETRY));
            return false;
        }
    }

    /**
     * Retries due pending sandbox deletions idempotently.
     *
     * @return number of sandbox copies successfully removed in this run
     */
    public int sendDue() {
        if (!link.linkedProduction()) {
            return 0;
        }
        Instant now = clock.instant();
        List<PendingSandboxDeletionPort.PendingDeletion> due = pending.due(now, BATCH_SIZE);
        int completed = 0;
        for (PendingSandboxDeletionPort.PendingDeletion item : due) {
            try {
                sandboxLink.deleteSandboxLocation(item.tenantId(), item.ownerUsername());
                pending.markDone(item.tenantId());
                completed++;
                log.info("Retry succeeded: removed sandbox copy of location '{}' for owner '{}'",
                        item.tenantId(), item.ownerUsername());
            } catch (RuntimeException e) {
                int nextAttempts = item.attempts() + 1;
                Instant nextAt = now.plus(backoff(item.attempts()));
                pending.reschedule(item.tenantId(), nextAttempts, nextAt, e.getMessage(), now);
                log.warn("Retry {} failed for sandbox deletion of location '{}'; next attempt at {}: {}",
                        nextAttempts, item.tenantId(), nextAt, e.getMessage());
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
