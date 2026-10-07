package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Map;

@Controller
@RequestMapping("/{tenantId}/api/backoffice/maintenance")
@RequiredArgsConstructor
@Slf4j
public class TenantMaintenanceController {

    private final TenantBackupRestorePort tenantBackupRestorePort;

    @GetMapping("/backup")
    public void downloadBackup(@PathVariable String tenantId, HttpServletResponse response) {
        log.info("Requested database and media backup for tenant: {}", tenantId);
        java.nio.file.Path archive = null;
        try {
            // Complete the archive before committing HTTP headers: a failed export must not look
            // like a successful download containing a truncated ZIP.
            archive = java.nio.file.Files.createTempFile("fastbite-backup-", ".zip");
            try (var output = java.nio.file.Files.newOutputStream(archive)) {
                tenantBackupRestorePort.exportBackup(tenantId, output);
            }
            response.setContentType("application/zip");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + tenantId + "_backup.zip");
            response.setContentLengthLong(java.nio.file.Files.size(archive));
            java.nio.file.Files.copy(archive, response.getOutputStream());
            log.info("Backup successfully streamed for tenant: {}", tenantId);
        } catch (Exception e) {
            log.error("Failed to stream backup for tenant: " + tenantId, e);
            if (!response.isCommitted()) {
                response.reset();
                response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            }
        } finally {
            if (archive != null) {
                try { java.nio.file.Files.deleteIfExists(archive); }
                catch (java.io.IOException e) { log.warn("Could not remove temporary backup archive", e); }
            }
        }
    }

    @GetMapping("/export-production-bundle")
    public void downloadProductionBundle(@PathVariable String tenantId, HttpServletResponse response) {
        log.info("Requested clean production promotion bundle for tenant: {}", tenantId);
        java.nio.file.Path archive = null;
        try {
            archive = java.nio.file.Files.createTempFile("fastbite-bundle-", ".zip");
            try (var output = java.nio.file.Files.newOutputStream(archive)) {
                tenantBackupRestorePort.exportProductionBundle(tenantId, output);
            }
            response.setContentType("application/zip");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + tenantId + "_production_bundle.zip");
            response.setContentLengthLong(java.nio.file.Files.size(archive));
            java.nio.file.Files.copy(archive, response.getOutputStream());
            log.info("Production promotion bundle successfully streamed for tenant: {}", tenantId);
        } catch (Exception e) {
            log.error("Failed to stream production bundle for tenant: " + tenantId, e);
            if (!response.isCommitted()) {
                response.reset();
                response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            }
        } finally {
            if (archive != null) {
                try { java.nio.file.Files.deleteIfExists(archive); }
                catch (java.io.IOException e) { log.warn("Could not remove temporary bundle archive", e); }
            }
        }
    }

    @PostMapping("/import-production-bundle")
    @ResponseBody
    public ResponseEntity<Map<String, String>> uploadProductionBundle(
            @PathVariable String tenantId,
            @RequestParam("file") MultipartFile file) {
        log.info("Requested production promotion bundle import for tenant: {}", tenantId);
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Uploaded bundle file is empty."));
        }

        try (InputStream is = file.getInputStream()) {
            tenantBackupRestorePort.importProductionBundle(tenantId, is);
            log.info("Production bundle imported successfully for tenant: {}", tenantId);
            return ResponseEntity.ok(Map.of("status", "success", "message", "Production bundle successfully imported into location."));
        } catch (Exception e) {
            log.error("Failed to import production bundle for tenant: " + tenantId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to import bundle: " + e.getMessage()));
        }
    }

    @PostMapping("/restore")
    @ResponseBody
    public ResponseEntity<Map<String, String>> uploadRestore(
            @PathVariable String tenantId,
            @RequestParam("file") MultipartFile file) {
        log.info("Requested database and media restore for tenant: {}", tenantId);
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Uploaded backup file is empty."));
        }

        try (InputStream is = file.getInputStream()) {
            tenantBackupRestorePort.importRestore(tenantId, is);
            log.info("Restore completed successfully for tenant: {}", tenantId);
            return ResponseEntity.ok(Map.of("status", "success", "message", "Database and files successfully restored."));
        } catch (Exception e) {
            log.error("Failed to restore backup for tenant: " + tenantId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to restore: " + e.getMessage()));
        }
    }

    @PostMapping("/clean")
    @ResponseBody
    public ResponseEntity<Map<String, String>> cleanData(@PathVariable String tenantId) {
        log.info("Requested database reset/clean for tenant: {}", tenantId);
        try {
            tenantBackupRestorePort.cleanTenantData(tenantId);
            log.info("Tenant reset/clean completed successfully: {}", tenantId);
            return ResponseEntity.ok(Map.of("status", "success", "message", "Database and uploads successfully reset."));
        } catch (Exception e) {
            log.error("Failed to reset tenant data for: " + tenantId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to reset data: " + e.getMessage()));
        }
    }

    @GetMapping("/templates")
    @ResponseBody
    public ResponseEntity<java.util.List<TenantBackupRestorePort.DemoTemplateDescriptor>> getDemoTemplates(
            @PathVariable String tenantId,
            java.util.Locale locale) {
        return ResponseEntity.ok(tenantBackupRestorePort.getAvailableTemplates(locale));
    }

    @PostMapping("/restore-demo")
    @ResponseBody
    public ResponseEntity<Map<String, String>> restoreDemo(
            @PathVariable String tenantId,
            @RequestParam(value = "template", required = false) String template) {
        String templateName = (template != null && !template.isBlank()) ? template : "kebab";
        log.info("Requested restore from demo package '{}' for tenant: {}", templateName, tenantId);
        try {
            tenantBackupRestorePort.importDemoTemplate(tenantId, templateName);
            log.info("Demo restore completed successfully for tenant: {} using template: {}", tenantId, templateName);
            return ResponseEntity.ok(Map.of("status", "success", "message", "Restaurant template demo data successfully loaded."));
        } catch (IllegalArgumentException e) {
            log.warn("Demo template not found for tenant: {}: {}", tenantId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to restore demo for tenant: " + tenantId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to load demo: " + e.getMessage()));
        }
    }
}
