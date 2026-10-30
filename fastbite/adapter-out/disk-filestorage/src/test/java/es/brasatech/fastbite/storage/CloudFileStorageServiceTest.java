package es.brasatech.fastbite.storage;

import es.brasatech.fastbite.domain.exception.ImageUploadRejection;
import es.brasatech.fastbite.domain.image.ImageInfo;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class CloudFileStorageServiceTest {

    @TempDir
    Path tempDir;

    private DiskFileStorageService diskStorage;
    private CloudFileStorageService cloudStorage;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        diskStorage = new DiskFileStorageService();
        ReflectionTestUtils.setField(diskStorage, "uploadDirectory", tempDir.resolve("images").toString());
        ReflectionTestUtils.setField(diskStorage, "allowedExtensions", "jpg,jpeg,png,gif,webp");
        diskStorage.init();

        cloudStorage = new CloudFileStorageService(
                diskStorage,
                "test-fastbite-bucket",
                "https://storage.googleapis.com",
                "jpg,jpeg,png,webp",
                2
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void testUploadWithDiskFallbackWhenCloudUnavailable() throws IOException {
        TenantContext.setCurrentTenant("testrestaurant");

        byte[] fakeImageContent = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/6X8AAAAASUVORK5CYII=");
        ByteArrayInputStream is = new ByteArrayInputStream(fakeImageContent);

        // When GCP endpoint is not reachable or unauthenticated, it must cleanly fall back to local disk storage
        ImageInfo info = cloudStorage.uploadImage(is, "burger.png", "menu");

        assertNotNull(info);
        assertTrue(info.name().matches("[0-9a-f]{32}\\.png"));
        assertTrue(info.url().contains("/user-images/testrestaurant/menu/"));
        assertEquals("menu", info.folder());
    }

    @Test
    void testDisallowedExtensionThrowsException() {
        TenantContext.setCurrentTenant("testrestaurant");
        byte[] fakeFile = "malicious-exe".getBytes(StandardCharsets.UTF_8);

        assertThrows(ImageUploadRejection.class, () ->
                cloudStorage.uploadImage(new ByteArrayInputStream(fakeFile), "evil.exe", ""));
    }

    @Test
    void testGetUploadDirectoryReturnsCloudUri() {
        assertEquals("gs://test-fastbite-bucket", cloudStorage.getUploadDirectory());
    }
}
