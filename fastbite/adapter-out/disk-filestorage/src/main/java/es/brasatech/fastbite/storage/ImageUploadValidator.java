package es.brasatech.fastbite.storage;

import es.brasatech.fastbite.domain.exception.ImageUploadRejection;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Bounded signature and filename checks before an image reaches either storage adapter. */
final class ImageUploadValidator {
    private ImageUploadValidator() { }

    record ValidatedImage(byte[] bytes, String contentType) { }

    static ValidatedImage validate(InputStream input, String filename, String folder, String allowedExtensions,
            int maxMiB) throws IOException {
        if (maxMiB < 1 || maxMiB > 1024) throw new IllegalStateException("Invalid image upload size configuration");
        if (filename == null || filename.isBlank() || filename.length() > 255 || filename.contains("/")
                || filename.contains("\\") || filename.chars().anyMatch(Character::isISOControl)) throw invalid();
        if (folder != null && !folder.isBlank() && !folder.trim().matches("[\\p{L}\\p{N} _-]{1,100}")) throw invalid();
        int dot = filename.lastIndexOf('.');
        String extension = dot > 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        List<String> allowed = Arrays.stream(allowedExtensions.split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT)).toList();
        if (!allowed.contains(extension) || !List.of("jpg", "jpeg", "png", "webp").contains(extension)) throw invalid();
        int maxBytes = Math.multiplyExact(maxMiB, 1024 * 1024);
        byte[] bytes = input.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) throw new ImageUploadRejection("image.upload.tooLarge", "The image exceeds the maximum file size.", maxMiB);
        String contentType = contentType(bytes);
        boolean matches = switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg".equals(contentType);
            case "png" -> "image/png".equals(contentType);
            case "webp" -> "image/webp".equals(contentType);
            default -> false;
        };
        if (!matches) throw invalid();
        return new ValidatedImage(bytes, contentType);
    }

    private static String contentType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) return "image/jpeg";
        if (bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})) return "image/png";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        return "";
    }

    private static ImageUploadRejection invalid() {
        return new ImageUploadRejection("image.upload.invalid", "Choose a valid JPG, PNG or WebP image.");
    }
}
