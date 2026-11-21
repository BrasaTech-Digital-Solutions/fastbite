package es.brasatech.fastbite.application.digitalsign;

import java.time.Instant;
import java.util.List;

/**
 * Representation of a Digital Sign request returned by {@code POST /requests} and
 * {@code GET /requests/{id}}.
 *
 * <p>{@link RequestSigner#toString()} redacts {@code signingUrl} so it is never logged accidentally.
 */
public record DigitalSignRequest(
        String id,
        String status,
        String externalReference,
        String title,
        Instant createdAt,
        Instant expiresAt,
        Instant completedAt,
        List<RequestSigner> signers,
        RequestDocuments documents,
        boolean test) {

    public DigitalSignRequest(
            String id,
            String status,
            String externalReference,
            String title,
            Instant createdAt,
            Instant expiresAt,
            Instant completedAt,
            List<RequestSigner> signers,
            RequestDocuments documents) {
        this(id, status, externalReference, title, createdAt, expiresAt, completedAt, signers, documents, false);
    }

    public DigitalSignRequest {
        signers = signers == null ? List.of() : List.copyOf(signers);
        documents = documents == null ? new RequestDocuments(false, false) : documents;
    }

    public record RequestSigner(
            String role,
            String name,
            String email,
            String language,
            String status,
            Instant signedAt,
            String signingUrl,
            List<RequestSignerFile> files,
            String signatureMethod,
            String nif,
            String certificateStatus,
            Object certificate) {

        public RequestSigner {
            files = files == null ? List.of() : List.copyOf(files);
        }

        @Override
        public String toString() {
            return "RequestSigner{"
                    + "role='" + role + '\''
                    + ", name='" + name + '\''
                    + ", status='" + status + '\''
                    + ", signedAt=" + signedAt
                    + ", signingUrl=" + (signingUrl != null && !signingUrl.isBlank() ? "[REDACTED]" : "null")
                    + ", signatureMethod='" + signatureMethod + '\''
                    + ", certificateStatus='" + certificateStatus + '\''
                    + '}';
        }
    }

    public record RequestSignerFile(
            String label,
            boolean required,
            String status) {
    }

    public record RequestDocuments(
            boolean signedDocument,
            boolean evidenceReport) {
    }
}
