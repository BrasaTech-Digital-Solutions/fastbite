package es.brasatech.fastbite.application.verifactu;

import java.util.Optional;
import java.util.UUID;

/**
 * Private file storage port for VERI*FACTU signed Anexo I PDFs and Digital Sign evidence reports.
 * Implementations must store files outside the public images directory and never expose them publicly.
 */
public interface VerifactuAuthorizationDocumentStorage {

    record StoredDocument(String relativePath, String sha256) {
    }

    StoredDocument storeSignedPdf(String nif, UUID authorizationId, byte[] pdfBytes);

    StoredDocument storeEvidencePdf(String nif, UUID authorizationId, byte[] pdfBytes);

    Optional<byte[]> readDocument(String relativePath);

    boolean exists(String relativePath);
}
