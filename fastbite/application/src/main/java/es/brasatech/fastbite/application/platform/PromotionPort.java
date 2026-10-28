package es.brasatech.fastbite.application.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

public interface PromotionPort {

    enum State {
        PREVIEWED, APPLYING, APPLIED, UNDONE, EXPIRED, FAILED
    }

    record Entry(
            String id,
            String tenantId,
            String ownerUsername,
            State state,
            String tokenHash,
            String bundlePath,
            String snapshotPath,
            String snapshotName,
            String summary,
            Instant createdAt,
            Instant expiresAt,
            Instant appliedAt,
            Instant undoneAt
    ) {}

    /**
     * Claims an exclusive lease for the tenant for up to leaseDuration.
     * Returns the claimed_at timestamp if granted, empty if already held.
     */
    OptionalLong claimLease(String tenantId, Instant now, Duration leaseDuration);

    /**
     * Releases the lease only if held by the given claimedAt timestamp.
     */
    void releaseLease(String tenantId, long claimedAt);

    /**
     * Marks all existing PREVIEWED rows for this tenant as EXPIRED and returns their bundle paths for deletion.
     */
    List<String> expireOtherPreviews(String tenantId);

    /**
     * Records a newly generated promotion preview.
     */
    void savePreview(Entry entry);

    /**
     * Finds a promotion entry by its token SHA-256 hash.
     */
    Optional<Entry> findByTokenHash(String tokenHash);

    /**
     * Finds a promotion entry by its unique ID.
     */
    Optional<Entry> findById(String id);

    /**
     * Atomically transitions a promotion from PREVIEWED to APPLYING.
     * Returns true if 1 row updated (token consumed), false if already used, expired, or mismatch.
     */
    boolean consumeToken(String tokenHash, String tenantId, String ownerUsername, Instant now);

    /**
     * Marks the promotion as APPLIED and records pre-promotion snapshot details.
     */
    void markApplied(String id, String snapshotPath, String snapshotName, Instant appliedAt);

    /**
     * Marks the promotion as UNDONE.
     */
    void markUndone(String id, Instant undoneAt);

    /**
     * Marks the promotion as FAILED.
     */
    void markFailed(String id);

    /**
     * Periodic sweep across all tenants:
     * - expires PREVIEWED rows past expires_at
     * - marks APPLYING rows older than leaseDuration as FAILED
     * Returns bundle/snapshot paths of swept rows for file deletion.
     */
    List<String> sweep(Instant now, Duration leaseDuration);

    /**
     * Returns the most recent promotion in state APPLIED or UNDONE (ordered by applied_at DESC).
     */
    Optional<Entry> findLatestAppliedOrUndone(String tenantId);

    /**
     * Returns APPLIED or UNDONE promotions beyond the keepCount newest (ordered by applied_at DESC).
     */
    List<Entry> findAppliedOrUndoneBeyond(String tenantId, int keepCount);

    /**
     * Deletes a promotion record from the database.
     */
    void deleteRow(String id);
}
