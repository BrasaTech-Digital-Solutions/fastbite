package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.storage.FileStorageService;
import es.brasatech.fastbite.domain.image.ImageInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller for image management in BackOffice.
 */
@RestController
@RequestMapping("/api/backoffice/images")
@RequiredArgsConstructor
@Slf4j
public class ImageController {

    private final FileStorageService fileStorageService;

    /**
     * Upload an image file.
     *
     * @param file   the image file to upload
     * @param folder optional folder/group name to organize the image
     * @return the uploaded image information
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folder", required = false) String folder) {
        try {
            ImageInfo imageInfo = fileStorageService.uploadImage(file.getInputStream(), file.getOriginalFilename(),
                    folder);
            return ResponseEntity.ok(imageInfo);
        } catch (IOException e) {
            log.error("Error storing uploaded image", e);
            Map<String, String> error = new HashMap<>();
            error.put("error", "Unable to store image.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Get all system images grouped by folder.
     *
     * @return map of folder name to list of images
     */
    @GetMapping("/system")
    public ResponseEntity<Map<String, List<ImageInfo>>> getSystemImages() {
        Map<String, List<ImageInfo>> images = fileStorageService.listSystemImages();
        return ResponseEntity.ok(images);
    }

    /**
     * Get all user-uploaded images grouped by folder.
     *
     * @return map of folder name to list of images
     */
    @GetMapping("/user")
    public ResponseEntity<Map<String, List<ImageInfo>>> getUserImages() {
        Map<String, List<ImageInfo>> images = fileStorageService.listUserImages();
        return ResponseEntity.ok(images);
    }

    /**
     * Get all images (both system and user) grouped by type and folder.
     *
     * @return combined map of all images
     */
    @GetMapping("/all")
    public ResponseEntity<Map<String, Object>> getAllImages() {
        Map<String, Object> result = new HashMap<>();
        result.put("system", fileStorageService.listSystemImages());
        result.put("user", fileStorageService.listUserImages());
        return ResponseEntity.ok(result);
    }

    /**
     * Debug endpoint to check image upload configuration.
     *
     * @return configuration details
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, String>> getImageConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("uploadDirectory", fileStorageService.getUploadDirectory());
        config.put("uploadDirectoryAbsolute",
                java.nio.file.Paths.get(fileStorageService.getUploadDirectory()).toAbsolutePath().toString());
        config.put("uploadDirectoryExists", String
                .valueOf(java.nio.file.Files.exists(java.nio.file.Paths.get(fileStorageService.getUploadDirectory()))));

        // Check environment variable
        String envVar = System.getenv("IMAGE_UPLOAD_DIR");
        config.put("IMAGE_UPLOAD_DIR_env", envVar != null ? envVar : "NOT SET");

        return ResponseEntity.ok(config);
    }
}
