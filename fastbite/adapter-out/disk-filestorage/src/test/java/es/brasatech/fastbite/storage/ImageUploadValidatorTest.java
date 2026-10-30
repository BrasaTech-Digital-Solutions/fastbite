package es.brasatech.fastbite.storage;

import es.brasatech.fastbite.domain.exception.ImageUploadRejection;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class ImageUploadValidatorTest {
    private static final String ALLOWED = "jpg,jpeg,png,webp";
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/6X8AAAAASUVORK5CYII=");
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
    private static final byte[] WEBP = {'R','I','F','F',0,0,0,0,'W','E','B','P'};
    @TempDir Path directory;
    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test void matchesSignaturesAndExtensionsWithoutTrustingTheName() throws Exception {
        assertThat(validate(PNG, "a.PnG").contentType()).isEqualTo("image/png");
        assertThat(validate(JPEG, "a.jpeg").contentType()).isEqualTo("image/jpeg");
        assertThat(validate(WEBP, "a.webp").contentType()).isEqualTo("image/webp");
        for (String name : new String[]{"fake.jpg", "fake.webp", "a.gif", "../a.png", "a\\b.png", "a"}) {
            assertThatThrownBy(() -> validate(PNG, name)).isInstanceOf(ImageUploadRejection.class);
        }
        assertThatThrownBy(() -> validate(new byte[]{(byte) 0x89, 'P'}, "short.png")).isInstanceOf(ImageUploadRejection.class);
        assertThatThrownBy(() -> ImageUploadValidator.validate(new ByteArrayInputStream(PNG), "a.png", "../other", ALLOWED, 2)).isInstanceOf(ImageUploadRejection.class);
    }

    @Test void contentNameUsesCanonicalExtensionAndSha256Prefix() throws Exception {
        assertThat(validate(PNG, "a.png").filename()).isEqualTo("38517b3f73391766f8fa677bbeeaf183.png");
        assertThat(validate(JPEG, "a.jpeg").filename()).endsWith(".jpg");
        assertThat(validate(JPEG, "b.jpg").filename()).isEqualTo(validate(JPEG, "a.jpeg").filename());
        assertThat(validate(WEBP, "a.webp").filename()).endsWith(".webp");
    }

    @Test void limitReadsOnlyOneBytePastConfiguredMaximum() throws Exception {
        byte[] boundary = new byte[1024 * 1024];
        System.arraycopy(PNG, 0, boundary, 0, PNG.length);
        assertThat(ImageUploadValidator.validate(new ByteArrayInputStream(boundary), "a.png", "", ALLOWED, 1).bytes()).hasSize(boundary.length);
        byte[] oversized = new byte[boundary.length + 5];
        System.arraycopy(boundary, 0, oversized, 0, boundary.length);
        ByteArrayInputStream stream = new ByteArrayInputStream(oversized);
        assertThatThrownBy(() -> ImageUploadValidator.validate(stream, "a.png", "", ALLOWED, 1))
                .isInstanceOf(ImageUploadRejection.class).hasMessageContaining("maximum");
        assertThat(stream.available()).isEqualTo(4);
    }

    @Test void diskRejectsInvalidBytesWithoutWritingAndKeepsOldGifVisible() throws Exception {
        DiskFileStorageService disk = new DiskFileStorageService();
        ReflectionTestUtils.setField(disk, "uploadDirectory", directory.resolve("images").toString());
        ReflectionTestUtils.setField(disk, "allowedExtensions", ALLOWED);
        TenantContext.setCurrentTenant("testtenant");
        assertThatThrownBy(() -> disk.uploadImage(new ByteArrayInputStream("not image".getBytes()), "x.png", ""))
                .isInstanceOf(ImageUploadRejection.class);
        assertThat(directory.resolve("images")).doesNotExist();
        Path existing = Files.createDirectories(directory.resolve("images/testtenant")).resolve("old.gif");
        Files.write(existing, new byte[]{'G','I','F'});
        assertThat(disk.listUserImages()).containsKey("root");
        assertThat(disk.uploadImage(new ByteArrayInputStream(PNG), "a.png", "menu").url()).contains("/user-images/testtenant/menu/");
    }

    @Test void contentAddressedNamesChangeOnlyWhenBytesChangeAndNeverOverwrite() throws Exception {
        DiskFileStorageService disk = new DiskFileStorageService();
        ReflectionTestUtils.setField(disk, "uploadDirectory", directory.resolve("images").toString());
        ReflectionTestUtils.setField(disk, "allowedExtensions", ALLOWED);
        TenantContext.setCurrentTenant("tenant");
        var first = disk.uploadImage(new ByteArrayInputStream(PNG), "first.png", "menu");
        var same = disk.uploadImage(new ByteArrayInputStream(PNG), "renamed.png", "menu");
        byte[] changed = PNG.clone(); changed[changed.length - 1] ^= 1;
        var replacement = disk.uploadImage(new ByteArrayInputStream(changed), "first.png", "menu");
        assertThat(first.name()).matches("[0-9a-f]{32}\\.png");
        assertThat(same.url()).isEqualTo(first.url());
        assertThat(replacement.url()).isNotEqualTo(first.url());
        assertThat(Files.readAllBytes(directory.resolve("images/tenant/menu").resolve(first.name()))).isEqualTo(PNG);
        assertThat(Files.readAllBytes(directory.resolve("images/tenant/menu").resolve(replacement.name()))).isEqualTo(changed);
        try (var files = Files.list(directory.resolve(".image-staging"))) {
            assertThat(files).isEmpty();
        }
        Files.write(directory.resolve("images/tenant/menu").resolve(first.name()), changed);
        assertThatThrownBy(() -> disk.uploadImage(new ByteArrayInputStream(PNG), "again.png", "menu"))
                .isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(directory.resolve("images/tenant/menu").resolve(first.name()))).isEqualTo(changed);
        try (var files = Files.list(directory.resolve(".image-staging"))) { assertThat(files).isEmpty(); }
    }

    @Test void failedStagingDoesNotPublishAHashFile() throws Exception {
        DiskFileStorageService disk = new DiskFileStorageService();
        ReflectionTestUtils.setField(disk, "uploadDirectory", directory.resolve("images").toString());
        ReflectionTestUtils.setField(disk, "allowedExtensions", ALLOWED);
        Files.writeString(directory.resolve(".image-staging"), "not a directory");
        TenantContext.setCurrentTenant("tenant");
        assertThatThrownBy(() -> disk.uploadImage(new ByteArrayInputStream(PNG), "first.png", "menu"))
                .isInstanceOf(IOException.class);
        assertThat(directory.resolve("images/tenant/menu").resolve(validate(PNG, "first.png").filename())).doesNotExist();
    }

    private static ImageUploadValidator.ValidatedImage validate(byte[] data, String name) throws Exception {
        return ImageUploadValidator.validate(new ByteArrayInputStream(data), name, "menu", ALLOWED, 2);
    }
}
