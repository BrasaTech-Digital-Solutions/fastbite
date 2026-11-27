package es.brasatech.fastbite.application.platform;

import java.time.Instant;
import java.util.List;

/**
 * Persists sandbox lifecycle sync retries (hide, restore, erase) when production hides,
 * restores, deletes, or closes a location and the immediate sandbox call fails.
 */
public interface PendingSandboxDeletionPort {

    enum Action {
        HIDE,
        RESTORE,
        ERASE
    }

    record PendingDeletion(
            String tenantId,
            String ownerUsername,
            Action action,
            int attempts,
            Instant nextAttemptAt,
            String lastError) {
        public PendingDeletion(
                String tenantId,
                String ownerUsername,
                int attempts,
                Instant nextAttemptAt,
                String lastError) {
            this(tenantId, ownerUsername, Action.ERASE, attempts, nextAttemptAt, lastError);
        }
    }

    default void enqueue(String tenantId, String ownerUsername, String lastError, Instant now, Instant nextAttemptAt) {
        enqueue(tenantId, ownerUsername, Action.ERASE, lastError, now, nextAttemptAt);
    }

    void enqueue(String tenantId, String ownerUsername, Action action, String lastError, Instant now, Instant nextAttemptAt);

    List<PendingDeletion> due(Instant now, int limit);

    /**
     * Atomically claims a due sandbox sync entry so concurrent instances do not dispatch it twice.
     */
    default boolean claim(String tenantId, Instant now, Instant staleBefore) {
        return true;
    }

    void markDone(String tenantId);

    void reschedule(String tenantId, int attempts, Instant nextAttemptAt, String lastError, Instant now);
}
