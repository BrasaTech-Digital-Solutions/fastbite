package es.brasatech.fastbite.application.verifactu;

import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorization;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound persistence port for per-NIF VERI*FACTU authorisations and Digital Sign webhook
 * deduplication records.
 */
public interface VerifactuAuthorizationStore {

    VerifactuAuthorization insert(VerifactuAuthorization authorization);

    /**
     * Compare-and-set update guarded by {@code authorization.version()}.
     * If a concurrent writer already advanced the row, returns the current persisted row without overwriting it.
     */
    VerifactuAuthorization update(VerifactuAuthorization authorization);

    Optional<VerifactuAuthorization> findById(UUID id);

    Optional<VerifactuAuthorization> findByDigitalSignRequestId(String requestId);

    Optional<VerifactuAuthorization> findValidByNif(String nif);

    Optional<VerifactuAuthorization> findActiveByNif(String nif);

    Optional<VerifactuAuthorization> findLatestByNif(String nif);

    int nextAttemptForNif(String nif);

    /** True only when the NIF currently has a VALID authorisation with both PDFs stored. */
    boolean hasValidAuthorization(String nif);

    /**
     * True when the NIF had a stored authorisation valid at {@code issuedAt}:
     * {@code valid_from <= issuedAt && (revoked_at == null || issuedAt < revoked_at)} with both PDFs stored.
     */
    boolean wasAuthorizedAt(String nif, Instant issuedAt);

    /**
     * Returns {@code DOWNLOAD_FAILED} authorisations and stale {@code IN_PROGRESS} authorisations
     * (whose {@code COALESCE(last_synced_at, created_at) <= staleBefore}) for reconciliation.
     */
    List<VerifactuAuthorization> findDueForReconciliation(Instant staleBefore, int limit);

    /**
     * Atomically records a webhook event ID if not seen before.
     *
     * @return {@code true} if this is a new event, {@code false} if already recorded (duplicate).
     */
    boolean recordWebhookEventIfNew(String eventId, String eventType, String requestId, Instant receivedAt);

    void markWebhookEventProcessed(String eventId, Instant processedAt);

    /** Removes a webhook deduplication record so a failed delivery can be retried by Digital Sign. */
    void deleteWebhookEvent(String eventId);

    /** Deletes recorded webhook events with {@code received_at < cutoff} and returns the deleted count. */
    int purgeWebhookEventsOlderThan(Instant cutoff);
}
