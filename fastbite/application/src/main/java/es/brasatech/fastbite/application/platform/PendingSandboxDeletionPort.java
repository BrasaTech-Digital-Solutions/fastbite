package es.brasatech.fastbite.application.platform;

import java.time.Instant;
import java.util.List;

/**
 * Persists sandbox deletion retries when production deleted or closed a location
 * and the immediate sandbox cleanup call failed.
 */
public interface PendingSandboxDeletionPort {

    record PendingDeletion(
            String tenantId,
            String ownerUsername,
            int attempts,
            Instant nextAttemptAt,
            String lastError) {
    }

    void enqueue(String tenantId, String ownerUsername, String lastError, Instant now, Instant nextAttemptAt);

    List<PendingDeletion> due(Instant now, int limit);

    void markDone(String tenantId);

    void reschedule(String tenantId, int attempts, Instant nextAttemptAt, String lastError, Instant now);
}
