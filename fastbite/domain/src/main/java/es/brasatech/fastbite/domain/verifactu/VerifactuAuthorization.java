package es.brasatech.fastbite.domain.verifactu;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-NIF VERI*FACTU authorisation record (Anexo I of AEAT Resolution of 18 Dec 2024).
 *
 * <p>Two locations belonging to the same NIF share a single {@code VALID} authorisation.
 * Records and their stored PDFs are kept for fiscal retention even after a location closes.
 */
public record VerifactuAuthorization(
        UUID id,
        String nif,
        String legalName,
        VerifactuAuthorizationStatus status,
        String digitalSignRequestId,
        String idempotencyKey,
        int attempt,
        String templateId,
        String signerName,
        String certificateNif,
        String signedPdfPath,
        String signedPdfSha256,
        String evidencePdfPath,
        String evidencePdfSha256,
        String failureReason,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant validFrom,
        Instant revokedAt,
        Instant lastSyncedAt,
        int version,
        int syncFailureCount,
        Instant syncFailureSince) {

    public VerifactuAuthorization(
            UUID id,
            String nif,
            String legalName,
            VerifactuAuthorizationStatus status,
            String digitalSignRequestId,
            String idempotencyKey,
            int attempt,
            String templateId,
            String signerName,
            String certificateNif,
            String signedPdfPath,
            String signedPdfSha256,
            String evidencePdfPath,
            String evidencePdfSha256,
            String failureReason,
            String createdBy,
            Instant createdAt,
            Instant updatedAt,
            Instant validFrom,
            Instant revokedAt,
            Instant lastSyncedAt,
            int version) {
        this(id, nif, legalName, status, digitalSignRequestId, idempotencyKey, attempt, templateId,
                signerName, certificateNif, signedPdfPath, signedPdfSha256, evidencePdfPath, evidencePdfSha256,
                failureReason, createdBy, createdAt, updatedAt, validFrom, revokedAt, lastSyncedAt, version, 0, null);
    }

    public VerifactuAuthorization(
            UUID id,
            String nif,
            String legalName,
            VerifactuAuthorizationStatus status,
            String digitalSignRequestId,
            String idempotencyKey,
            int attempt,
            String templateId,
            String signerName,
            String certificateNif,
            String signedPdfPath,
            String signedPdfSha256,
            String evidencePdfPath,
            String evidencePdfSha256,
            String failureReason,
            String createdBy,
            Instant createdAt,
            Instant updatedAt,
            Instant validFrom,
            Instant revokedAt,
            Instant lastSyncedAt,
            int version,
            int syncFailureCount) {
        this(id, nif, legalName, status, digitalSignRequestId, idempotencyKey, attempt, templateId,
                signerName, certificateNif, signedPdfPath, signedPdfSha256, evidencePdfPath, evidencePdfSha256,
                failureReason, createdBy, createdAt, updatedAt, validFrom, revokedAt, lastSyncedAt, version, syncFailureCount, null);
    }

    public VerifactuAuthorization {
        Objects.requireNonNull(id, "id");
        if (nif == null || nif.isBlank()) {
            throw new IllegalArgumentException("nif must not be blank");
        }
        if (legalName == null || legalName.isBlank()) {
            throw new IllegalArgumentException("legalName must not be blank");
        }
        Objects.requireNonNull(status, "status");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("templateId must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0");
        }
        if (syncFailureCount < 0) {
            throw new IllegalArgumentException("syncFailureCount must be >= 0");
        }
    }

    /** True only when both the signed PDF and the evidence report are stored with SHA-256 digests. */
    public boolean hasStoredDocuments() {
        return isNonBlank(signedPdfPath) && isNonBlank(signedPdfSha256)
                && isNonBlank(evidencePdfPath) && isNonBlank(evidencePdfSha256);
    }

    /** True only when this authorisation is currently VALID, unrevoked, and has both PDFs stored. */
    public boolean isCurrentlyValid() {
        return status == VerifactuAuthorizationStatus.VALID
                && validFrom != null
                && revokedAt == null
                && hasStoredDocuments();
    }

    /**
     * True when this authorisation was valid at {@code issuedAt}:
     * {@code validFrom <= issuedAt && (revokedAt == null || issuedAt < revokedAt)} with both PDFs stored.
     */
    public boolean wasValidAt(Instant issuedAt) {
        if (issuedAt == null || validFrom == null || !hasStoredDocuments()) {
            return false;
        }
        return !issuedAt.isBefore(validFrom) && (revokedAt == null || issuedAt.isBefore(revokedAt));
    }

    public VerifactuAuthorization withSync(
            VerifactuAuthorizationStatus nextStatus,
            String nextSignerName,
            String nextCertificateNif,
            String nextSignedPdfPath,
            String nextSignedPdfSha256,
            String nextEvidencePdfPath,
            String nextEvidencePdfSha256,
            String nextFailureReason,
            Instant nextValidFrom,
            Instant syncedAt) {
        return withSync(nextStatus, nextSignerName, nextCertificateNif, nextSignedPdfPath, nextSignedPdfSha256,
                nextEvidencePdfPath, nextEvidencePdfSha256, nextFailureReason, nextValidFrom, syncedAt,
                syncFailureCount, syncFailureSince);
    }

    public VerifactuAuthorization withSync(
            VerifactuAuthorizationStatus nextStatus,
            String nextSignerName,
            String nextCertificateNif,
            String nextSignedPdfPath,
            String nextSignedPdfSha256,
            String nextEvidencePdfPath,
            String nextEvidencePdfSha256,
            String nextFailureReason,
            Instant nextValidFrom,
            Instant syncedAt,
            int nextSyncFailureCount) {
        return withSync(nextStatus, nextSignerName, nextCertificateNif, nextSignedPdfPath, nextSignedPdfSha256,
                nextEvidencePdfPath, nextEvidencePdfSha256, nextFailureReason, nextValidFrom, syncedAt,
                nextSyncFailureCount, nextSyncFailureCount == 0 ? null : syncFailureSince);
    }

    public VerifactuAuthorization withSync(
            VerifactuAuthorizationStatus nextStatus,
            String nextSignerName,
            String nextCertificateNif,
            String nextSignedPdfPath,
            String nextSignedPdfSha256,
            String nextEvidencePdfPath,
            String nextEvidencePdfSha256,
            String nextFailureReason,
            Instant nextValidFrom,
            Instant syncedAt,
            int nextSyncFailureCount,
            Instant nextSyncFailureSince) {
        return new VerifactuAuthorization(
                id,
                nif,
                legalName,
                nextStatus,
                digitalSignRequestId,
                idempotencyKey,
                attempt,
                templateId,
                nextSignerName,
                nextCertificateNif,
                nextSignedPdfPath,
                nextSignedPdfSha256,
                nextEvidencePdfPath,
                nextEvidencePdfSha256,
                nextFailureReason,
                createdBy,
                createdAt,
                syncedAt,
                nextValidFrom,
                revokedAt,
                syncedAt,
                version,
                nextSyncFailureCount,
                nextSyncFailureSince);
    }

    public VerifactuAuthorization withVersion(int nextVersion) {
        return new VerifactuAuthorization(
                id,
                nif,
                legalName,
                status,
                digitalSignRequestId,
                idempotencyKey,
                attempt,
                templateId,
                signerName,
                certificateNif,
                signedPdfPath,
                signedPdfSha256,
                evidencePdfPath,
                evidencePdfSha256,
                failureReason,
                createdBy,
                createdAt,
                updatedAt,
                validFrom,
                revokedAt,
                lastSyncedAt,
                nextVersion,
                syncFailureCount,
                syncFailureSince);
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
