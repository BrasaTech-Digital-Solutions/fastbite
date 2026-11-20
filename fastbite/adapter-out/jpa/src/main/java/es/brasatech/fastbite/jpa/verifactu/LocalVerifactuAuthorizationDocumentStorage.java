package es.brasatech.fastbite.jpa.verifactu;

import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationDocumentStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Private filesystem storage for VERI*FACTU signed Anexo I PDFs and evidence reports.
 *
 * <p>Stores files under a sibling directory of {@code image.upload.directory} (e.g.
 * {@code /storage/verifactu-authorizations/<nif>/<authorizationId>/signed.pdf}), explicitly
 * rejecting paths inside {@code image.upload.directory}, path traversal, and symbolic links.
 */
@Component
public class LocalVerifactuAuthorizationDocumentStorage implements VerifactuAuthorizationDocumentStorage {

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final String SIGNED_FILE_NAME = "signed.pdf";
    private static final String EVIDENCE_FILE_NAME = "evidence.pdf";

    private final Path uploadRoot;
    private final Path storageRoot;

    @Autowired
    public LocalVerifactuAuthorizationDocumentStorage(
            @Value("${image.upload.directory:uploads/images}") String uploadDirectory) {
        if (uploadDirectory == null || uploadDirectory.isBlank()) {
            throw new IllegalArgumentException("image.upload.directory must not be blank");
        }
        this.uploadRoot = Paths.get(uploadDirectory).toAbsolutePath().normalize();
        this.storageRoot = resolveStorageRoot(uploadDirectory);
        if (this.storageRoot.startsWith(this.uploadRoot)) {
            throw new IllegalStateException(
                    "VERI*FACTU authorisation storage root must never be inside image.upload.directory");
        }
    }

    /**
     * Resolves the private sibling root next to {@code image.upload.directory} so
     * {@code /user-images/**} can never serve VERI*FACTU PDFs while the {@code /storage}
     * volume/mount still covers them.
     */
    public static Path resolveStorageRoot(String uploadDirectory) {
        Path uploadRoot = Paths.get(uploadDirectory).toAbsolutePath().normalize();
        Path fileName = uploadRoot.getFileName();
        Path candidate;
        if (fileName != null && "images".equalsIgnoreCase(fileName.toString())) {
            candidate = uploadRoot.resolveSibling("verifactu-authorizations").normalize();
        } else {
            String base = fileName != null ? fileName.toString() : "storage";
            candidate = uploadRoot.resolveSibling(base + "-verifactu-authorizations").normalize();
        }
        if (candidate.startsWith(uploadRoot)) {
            throw new IllegalStateException(
                    "VERI*FACTU authorisation storage root must never be inside image.upload.directory");
        }
        return candidate;
    }

    public Path storageRoot() {
        return storageRoot;
    }

    @Override
    public StoredDocument storeSignedPdf(String nif, UUID authorizationId, byte[] pdfBytes) {
        return storePdf(nif, authorizationId, SIGNED_FILE_NAME, pdfBytes);
    }

    @Override
    public StoredDocument storeEvidencePdf(String nif, UUID authorizationId, byte[] pdfBytes) {
        return storePdf(nif, authorizationId, EVIDENCE_FILE_NAME, pdfBytes);
    }

    @Override
    public Optional<byte[]> readDocument(String relativePath) {
        Optional<Path> resolved = resolveSafeRelativePath(relativePath);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Path target = resolved.get();
        if (!Files.isRegularFile(target) || Files.isSymbolicLink(target)) {
            return Optional.empty();
        }
        try {
            verifyCanonicalContainment(target);
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read stored VERI*FACTU PDF", e);
        }
    }

    @Override
    public boolean exists(String relativePath) {
        Optional<Path> resolved = resolveSafeRelativePath(relativePath);
        if (resolved.isEmpty()) {
            return false;
        }
        Path target = resolved.get();
        if (!Files.isRegularFile(target) || Files.isSymbolicLink(target)) {
            return false;
        }
        try {
            verifyCanonicalContainment(target);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private StoredDocument storePdf(String nif, UUID authorizationId, String fileName, byte[] pdfBytes) {
        validatePdf(pdfBytes);
        String safeNif = sanitizeNif(nif);
        Objects.requireNonNull(authorizationId, "authorizationId");

        String relativePath = safeNif + "/" + authorizationId + "/" + fileName;
        Path target = resolveSafeRelativePath(relativePath)
                .orElseThrow(() -> new IllegalArgumentException("Invalid VERI*FACTU document path"));
        String sha256 = sha256Hex(pdfBytes);

        try {
            rejectSymlinksInHierarchy(target);
            Files.createDirectories(target.getParent());
            verifyCanonicalContainment(target.getParent());

            Path tempFile = target.resolveSibling(fileName + "." + UUID.randomUUID() + ".tmp");
            try {
                Files.write(tempFile, pdfBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try {
                    Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException fallback) {
                    Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Best-effort cleanup of temp file.
                }
                throw e;
            }
            verifyCanonicalContainment(target);
            return new StoredDocument(relativePath, sha256);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store VERI*FACTU PDF", e);
        }
    }

    private Optional<Path> resolveSafeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return Optional.empty();
        }
        if (relativePath.contains("\\") || relativePath.startsWith("/") || relativePath.contains("..")) {
            return Optional.empty();
        }
        Path parsed;
        try {
            parsed = Paths.get(relativePath);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (parsed.isAbsolute()) {
            return Optional.empty();
        }
        Path target = storageRoot.resolve(parsed).normalize();
        if (!target.startsWith(storageRoot) || target.equals(storageRoot) || target.startsWith(uploadRoot)) {
            return Optional.empty();
        }
        rejectSymlinksInHierarchy(target);
        return Optional.of(target);
    }

    private void rejectSymlinksInHierarchy(Path target) {
        for (Path current = target; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("VERI*FACTU storage path must not contain symbolic links");
            }
            if (current.equals(storageRoot)) {
                break;
            }
        }
        if (Files.isSymbolicLink(storageRoot)) {
            throw new IllegalArgumentException("VERI*FACTU storage root must not be a symbolic link");
        }
    }

    private void verifyCanonicalContainment(Path existingPath) throws IOException {
        Path realRoot = storageRoot.toRealPath();
        Path realTarget = existingPath.toRealPath();
        if (!realTarget.startsWith(realRoot)) {
            throw new IllegalArgumentException("VERI*FACTU document path escapes private storage root");
        }
        if (Files.exists(uploadRoot)) {
            Path realUpload = uploadRoot.toRealPath();
            if (realTarget.startsWith(realUpload)) {
                throw new IllegalArgumentException("VERI*FACTU document path must never reside inside public uploads");
            }
        }
    }

    public static void validatePdf(byte[] bytes) {
        if (bytes == null || bytes.length < PDF_MAGIC.length) {
            throw new IllegalArgumentException("Downloaded document is empty or not a valid PDF");
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (bytes[i] != PDF_MAGIC[i]) {
                throw new IllegalArgumentException("Downloaded document does not start with %PDF- header");
            }
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String sanitizeNif(String nif) {
        if (nif == null || nif.isBlank()) {
            throw new IllegalArgumentException("NIF must not be blank");
        }
        String normalized = nif.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9]{8,16}")) {
            throw new IllegalArgumentException("Invalid NIF for VERI*FACTU document storage");
        }
        return normalized;
    }
}
