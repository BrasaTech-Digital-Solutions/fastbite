package es.brasatech.fastbite.domain.settings;

import java.time.Instant;

/**
 * A version of a restaurant's custom guest privacy notice PDF (advisor C4).
 * Every upload adds a new version; old versions are never overwritten or deleted.
 */
public record GuestPrivacyNotice(
        int version,
        String fileName,
        String storagePath,
        long fileSize,
        String sha256,
        String uploadedBy,
        Instant uploadedAt,
        boolean active
) {
}
