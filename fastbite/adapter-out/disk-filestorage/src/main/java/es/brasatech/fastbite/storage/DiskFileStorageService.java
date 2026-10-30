package es.brasatech.fastbite.storage;

import es.brasatech.fastbite.application.storage.FileStorageService;
import es.brasatech.fastbite.domain.image.ImageInfo;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Implementation of FileStorageService that stores files on the local disk.
 */
@Slf4j
@Service
@org.springframework.context.annotation.Primary
public class DiskFileStorageService implements FileStorageService {

    @Value("${image.upload.directory}")
    private String uploadDirectory;

    @Value("${image.upload.allowed-extensions}")
    private String allowedExtensions;

    @Value("${image.upload.max-file-size:2}")
    private int maxFileSizeMiB = 2;

    private static final String SYSTEM_IMAGES_PATH = "static/images";

    @jakarta.annotation.PostConstruct
    public void init() {
        Path uploadPath = Paths.get(uploadDirectory).toAbsolutePath().normalize();
        log.info("Disk File Storage Configuration:");
        log.info("  Upload Directory: {}", uploadPath);
        log.info("  Allowed Extensions: {}", allowedExtensions);

        try {
            Files.createDirectories(uploadPath);
        } catch (IOException e) {
            log.error("Failed to create upload directory: {}", e.getMessage());
        }
    }

    @Override
    public ImageInfo uploadImage(InputStream data, String originalFilename, String folder) throws IOException {
        var image = ImageUploadValidator.validate(data, originalFilename, folder, allowedExtensions, maxFileSizeMiB);

        Path uploadPath = Paths.get(uploadDirectory);
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId != null && !tenantId.trim().isEmpty()) {
            uploadPath = uploadPath.resolve(tenantId.trim());
        }
        if (folder != null && !folder.trim().isEmpty()) {
            uploadPath = uploadPath.resolve(folder.trim());
        }
        Files.createDirectories(uploadPath);

        String extension = getFileExtension(originalFilename);
        String filename = System.currentTimeMillis() + "_" + originalFilename;

        Path filePath = uploadPath.resolve(filename);
        Files.write(filePath, image.bytes());

        log.info("Stored file: {} to {}", filename, filePath);

        String urlPath = "/user-images/" +
                (tenantId != null && !tenantId.trim().isEmpty() ? tenantId.trim() + "/" : "") +
                (folder != null && !folder.trim().isEmpty() ? folder.trim() + "/" : "") +
                filename;

        return new ImageInfo(
                filename,
                urlPath,
                folder != null ? folder : "",
                "user",
                Files.size(filePath));
    }

    @Override
    public Map<String, List<ImageInfo>> listSystemImages() {
        Map<String, List<ImageInfo>> imagesByFolder = new HashMap<>();
        try {
            Resource resource = new ClassPathResource(SYSTEM_IMAGES_PATH);
            if (resource.exists()) {
                File imagesDir = resource.getFile();
                scanDirectory(imagesDir, "", imagesByFolder, "system", "/images");
            }
        } catch (IOException e) {
            log.warn("Could not read system images: {}", e.getMessage());
        }
        return imagesByFolder;
    }

    @Override
    public Map<String, List<ImageInfo>> listUserImages() {
        Map<String, List<ImageInfo>> imagesByFolder = new HashMap<>();
        try {
            Path uploadPath = Paths.get(uploadDirectory);
            String tenantId = TenantContext.getCurrentTenant();
            if (tenantId != null && !tenantId.trim().isEmpty()) {
                uploadPath = uploadPath.resolve(tenantId.trim());
            }
            if (Files.exists(uploadPath)) {
                File uploadsDir = uploadPath.toFile();
                scanDirectory(uploadsDir, "", imagesByFolder, "user", "/user-images" + (tenantId != null && !tenantId.trim().isEmpty() ? "/" + tenantId.trim() : ""));
            }
        } catch (Exception e) {
            log.warn("Could not read user images: {}", e.getMessage());
        }
        return imagesByFolder;
    }

    @Override
    public String getUploadDirectory() {
        return uploadDirectory;
    }

    private void scanDirectory(File dir, String relativePath, Map<String, List<ImageInfo>> imagesByFolder,
            String type, String urlPrefix) {
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                String newRelativePath = relativePath.isEmpty() ? file.getName() : relativePath + "/" + file.getName();
                scanDirectory(file, newRelativePath, imagesByFolder, type, urlPrefix);
            } else if (isImageFile(file.getName())) {
                String folder = relativePath.isEmpty() ? "root" : relativePath;
                String urlPath = urlPrefix + "/" + (relativePath.isEmpty() ? "" : relativePath + "/") + file.getName();

                ImageInfo imageInfo = new ImageInfo(
                        file.getName(),
                        urlPath,
                        folder,
                        type,
                        file.length());

                imagesByFolder.computeIfAbsent(folder, k -> new ArrayList<>()).add(imageInfo);
            }
        }
    }

    private boolean isImageFile(String filename) {
        String extension = getFileExtension(filename).toLowerCase();
        List<String> allowed = Arrays.asList(allowedExtensions.split(","));
        return allowed.contains(extension) || "gif".equals(extension);
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1) {
            return "";
        }
        return filename.substring(lastDot + 1);
    }
}
