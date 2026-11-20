package es.brasatech.fastbite.jpa.verifactu;

import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationDocumentStorage.StoredDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalVerifactuAuthorizationDocumentStorageTest {

    private static final byte[] SAMPLE_SIGNED_PDF = "%PDF-1.4\nSigned Anexo I content\n%%EOF".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SAMPLE_EVIDENCE_PDF = "%PDF-1.4\nDigital Sign Evidence Report\n%%EOF".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @Test
    void storesSignedAndEvidencePdfsInPrivateSiblingDirectoryWithSha256() {
        Path imagesDir = tempDir.resolve("images");
        LocalVerifactuAuthorizationDocumentStorage storage =
                new LocalVerifactuAuthorizationDocumentStorage(imagesDir.toString());

        UUID authId = UUID.randomUUID();
        StoredDocument signed = storage.storeSignedPdf("b09767898", authId, SAMPLE_SIGNED_PDF);
        StoredDocument evidence = storage.storeEvidencePdf("B09767898", authId, SAMPLE_EVIDENCE_PDF);

        assertThat(storage.storageRoot()).isEqualTo(tempDir.resolve("verifactu-authorizations").toAbsolutePath().normalize());
        assertThat(storage.storageRoot().startsWith(imagesDir.toAbsolutePath().normalize())).isFalse();
        assertThat(signed.relativePath()).isEqualTo("B09767898/" + authId + "/signed.pdf");
        assertThat(evidence.relativePath()).isEqualTo("B09767898/" + authId + "/evidence.pdf");
        assertThat(signed.sha256()).isEqualTo(LocalVerifactuAuthorizationDocumentStorage.sha256Hex(SAMPLE_SIGNED_PDF));
        assertThat(evidence.sha256()).isEqualTo(LocalVerifactuAuthorizationDocumentStorage.sha256Hex(SAMPLE_EVIDENCE_PDF));

        assertThat(Files.exists(imagesDir)).isFalse();
        assertThat(storage.exists(signed.relativePath())).isTrue();
        assertThat(storage.exists(evidence.relativePath())).isTrue();
        assertThat(storage.readDocument(signed.relativePath())).contains(SAMPLE_SIGNED_PDF);
        assertThat(storage.readDocument(evidence.relativePath())).contains(SAMPLE_EVIDENCE_PDF);
    }

    @Test
    void rejectsNonPdfPayloadsAndInvalidNifOrTraversal() {
        LocalVerifactuAuthorizationDocumentStorage storage =
                new LocalVerifactuAuthorizationDocumentStorage(tempDir.resolve("images").toString());
        UUID authId = UUID.randomUUID();

        assertThatThrownBy(() -> storage.storeSignedPdf("B09767898", authId, "<html>not a pdf</html>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("%PDF-");

        assertThatThrownBy(() -> storage.storeEvidencePdf("B09767898", authId, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> storage.storeSignedPdf("../escape", authId, SAMPLE_SIGNED_PDF))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(storage.exists("../images/logo.png")).isFalse();
        assertThat(storage.readDocument("../images/logo.png")).isEmpty();
        assertThat(storage.readDocument("/etc/passwd")).isEmpty();
        assertThat(storage.readDocument("B09767898\\file.pdf")).isEmpty();
    }
}
