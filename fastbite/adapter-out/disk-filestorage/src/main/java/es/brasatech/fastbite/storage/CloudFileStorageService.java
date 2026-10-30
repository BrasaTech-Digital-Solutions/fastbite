package es.brasatech.fastbite.storage;

import es.brasatech.fastbite.application.storage.FileStorageService;
import es.brasatech.fastbite.domain.image.ImageInfo;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Cloud Storage (Google Cloud Storage / compatible HTTP object store) adapter for FileStorageService.
 * Uses standard java.net.http.HttpClient with zero heavyweight cloud SDK dependencies,
 * ensuring clean GraalVM native image compatibility.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "fastbite.storage.cloud.bucket")
public class CloudFileStorageService implements FileStorageService {

    private final DiskFileStorageService diskFallback;
    private final String bucketName;
    private final String publicBaseUrl;
    private final String allowedExtensions;
    private final HttpClient httpClient;
    private final int maxFileSizeMiB;

    public CloudFileStorageService(
            DiskFileStorageService diskFallback,
            @Value("${fastbite.storage.cloud.bucket}") String bucketName,
            @Value("${fastbite.storage.cloud.public-url:https://storage.googleapis.com}") String publicBaseUrl,
            @Value("${image.upload.allowed-extensions:jpg,jpeg,png,webp}") String allowedExtensions,
            @Value("${image.upload.max-file-size:2}") int maxFileSizeMiB) {
        this.diskFallback = diskFallback;
        this.bucketName = bucketName;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        this.allowedExtensions = allowedExtensions;
        this.maxFileSizeMiB = maxFileSizeMiB;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("Cloud File Storage initialized for bucket: {}, publicBaseUrl: {}", this.bucketName, this.publicBaseUrl);
    }

    @Override
    public ImageInfo uploadImage(InputStream data, String originalFilename, String folder) throws IOException {
        var image = ImageUploadValidator.validate(data, originalFilename, folder, allowedExtensions, maxFileSizeMiB);

        String tenantId = TenantContext.getCurrentTenant();
        String safeTenant = (tenantId != null && !tenantId.isBlank()) ? tenantId.trim() : "default";
        String safeFolder = (folder != null && !folder.isBlank()) ? folder.trim() : "";
        String filename = System.currentTimeMillis() + "_" + originalFilename;

        String objectPath = safeTenant + "/" + (safeFolder.isEmpty() ? "" : safeFolder + "/") + filename;

        byte[] bytes = image.bytes();
        String contentType = image.contentType();

        // Upload to Google Cloud Storage JSON API or compatible endpoint
        boolean uploadedToCloud = false;
        try {
            String uploadUrl = String.format("https://storage.googleapis.com/upload/storage/v1/b/%s/o?uploadType=media&name=%s",
                    bucketName, java.net.URLEncoder.encode(objectPath, java.nio.charset.StandardCharsets.UTF_8));

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(uploadUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));

            // In GCP environments (Cloud Run/GKE/Compute), metadata token or Bearer token is passed if available
            String token = System.getenv("GCS_OAUTH_TOKEN");
            if (token != null && !token.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + token);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                uploadedToCloud = true;
                log.info("Uploaded {} to GCS bucket {} at {}", filename, bucketName, objectPath);
            } else {
                log.warn("Cloud storage upload returned status {}: {}. Falling back to disk storage.",
                        response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("Failed to upload to cloud storage bucket: {}. Falling back to disk storage.", e.getMessage());
        }

        if (!uploadedToCloud) {
            // Safe fallback to local disk
            return diskFallback.uploadImage(new java.io.ByteArrayInputStream(bytes), originalFilename, folder);
        }

        String publicUrl = String.format("%s/%s/%s", publicBaseUrl, bucketName, objectPath);
        return new ImageInfo(
                filename,
                publicUrl,
                safeFolder,
                "cloud",
                bytes.length);
    }

    @Override
    public Map<String, List<ImageInfo>> listSystemImages() {
        return diskFallback.listSystemImages();
    }

    @Override
    public Map<String, List<ImageInfo>> listUserImages() {
        return diskFallback.listUserImages();
    }

    @Override
    public String getUploadDirectory() {
        return "gs://" + bucketName;
    }

}
