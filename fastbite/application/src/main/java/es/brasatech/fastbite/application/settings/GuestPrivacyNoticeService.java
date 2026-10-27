package es.brasatech.fastbite.application.settings;

import es.brasatech.fastbite.domain.settings.GuestPrivacyNotice;
import es.brasatech.fastbite.domain.tenant.TenantContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Manages a restaurant's custom guest privacy notice PDFs (advisor C4).
 * Operates on the restaurant currently in {@link TenantContext}.
 */
public interface GuestPrivacyNoticeService {

    /** Maximum allowed PDF size: 5 MB. */
    int MAX_PDF_BYTES = 5 * 1024 * 1024;

    /** The currently active PDF notice, or empty if the restaurant uses the generated notice. */
    Optional<GuestPrivacyNotice> findActive();

    /** All uploaded versions for this restaurant, newest first. */
    List<GuestPrivacyNotice> history();

    /** The raw PDF bytes of the currently active notice, or empty if none is active. */
    Optional<byte[]> readActiveBytes();

    /**
     * Validates that {@code bytes} is a valid PDF (starts with {@code %PDF-} and is at most 5 MB),
     * stores it as a new version on disk without overwriting any prior file, and marks it active.
     */
    GuestPrivacyNotice upload(String originalFileName, byte[] bytes, String uploadedBy);

    /**
     * Switches back to the generated privacy notice by marking all versions inactive,
     * while keeping every historical row and file intact.
     */
    void useGeneratedNotice();

    default Optional<GuestPrivacyNotice> findActive(String tenantId) {
        return inTenant(tenantId, this::findActive);
    }

    default List<GuestPrivacyNotice> history(String tenantId) {
        return inTenant(tenantId, this::history);
    }

    default GuestPrivacyNotice upload(String tenantId, String originalFileName, byte[] bytes, String uploadedBy) {
        return inTenant(tenantId, () -> upload(originalFileName, bytes, uploadedBy));
    }

    default void useGeneratedNotice(String tenantId) {
        inTenant(tenantId, () -> {
            useGeneratedNotice();
            return null;
        });
    }

    private static <T> T inTenant(String tenantId, Supplier<T> work) {
        String previous = TenantContext.getCurrentTenant();
        try {
            TenantContext.setCurrentTenant(tenantId);
            return work.get();
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.setCurrentTenant(previous);
            }
        }
    }

    /** Validates that the uploaded bytes are a valid PDF (header {@code %PDF-}, size <= 5 MB). */
    static void validatePdf(byte[] bytes) {
        if (bytes == null || bytes.length < 5) {
            throw new IllegalArgumentException("Choose a valid PDF file (at most 5 MB).");
        }
        if (bytes.length > MAX_PDF_BYTES) {
            throw new IllegalArgumentException("The PDF file must not exceed 5 MB.");
        }
        if (bytes[0] != 0x25 || bytes[1] != 0x50 || bytes[2] != 0x44 || bytes[3] != 0x46 || bytes[4] != 0x2D) {
            throw new IllegalArgumentException("Only PDF files are accepted (checked by content, %PDF- header required).");
        }
    }

    /** Sanitizes the user-supplied filename to a plain basename of at most 255 characters. */
    static String sanitizeFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return "privacy.pdf";
        }
        String normalized = originalFileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String base = (slash >= 0 ? normalized.substring(slash + 1) : normalized).strip();
        if (base.isEmpty()) {
            return "privacy.pdf";
        }
        return base.length() > 255 ? base.substring(0, 255) : base;
    }

    /** Lowercase 64-character hex SHA-256 of the given bytes. */
    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
