package es.brasatech.fastbite.jpa.legal;

import es.brasatech.fastbite.application.settings.GuestPrivacyNoticeService;
import es.brasatech.fastbite.domain.settings.GuestPrivacyNotice;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.TestConfig;
import es.brasatech.fastbite.jpa.tenant.TenantBackupRestoreAdapter;
import es.brasatech.fastbite.jpa.tenant.TenantProvisionerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = TestConfig.class)
class GuestPrivacyNoticeIntegrationTest {

    @Autowired
    private GuestPrivacyNoticeService privacyNotices;

    @Autowired
    private TenantProvisionerAdapter provisioner;

    @Autowired
    private TenantBackupRestoreAdapter backupRestore;

    @Value("${image.upload.directory}")
    private String uploadDirectory;

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.clear();
        for (String tenant : List.of("privacyreject", "privacyversions", "privacysource", "privacydest")) {
            Path dir = GuestPrivacyNoticeServiceJpaImpl.resolveTenantDocumentsPath(uploadDirectory, tenant);
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void rejectsNonPdfAndOversizedFilesByContent() {
        String tenant = "privacyreject";
        provisioner.provisionTenant(tenant);
        TenantContext.setCurrentTenant(tenant);

        // Text or PNG disguised with .pdf filename is rejected
        byte[] fakePdf = "PNG\r\nNot a real PDF".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> privacyNotices.upload("privacy-notice.pdf", fakePdf, "ana"));

        // Too short (< 5 bytes)
        assertThrows(IllegalArgumentException.class,
                () -> privacyNotices.upload("short.pdf", "%PDF".getBytes(StandardCharsets.UTF_8), "ana"));

        // Oversized (> 5 MB)
        byte[] oversized = new byte[GuestPrivacyNoticeService.MAX_PDF_BYTES + 1];
        byte[] header = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, oversized, 0, header.length);
        assertThrows(IllegalArgumentException.class,
                () -> privacyNotices.upload("huge.pdf", oversized, "ana"));

        assertTrue(privacyNotices.findActive().isEmpty());
        assertTrue(privacyNotices.history().isEmpty());
    }

    @Test
    void uploadsNeverOverwriteOldVersionsAndStoresOutsidePublicImagesFolder() {
        String tenant = "privacyversions";
        provisioner.provisionTenant(tenant);
        TenantContext.setCurrentTenant(tenant);

        byte[] v1Bytes = "%PDF-1.4\nVersion 1 privacy notice\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
        byte[] v2Bytes = "%PDF-1.4\nVersion 2 privacy notice\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

        GuestPrivacyNotice v1 = privacyNotices.upload("notice-v1.pdf", v1Bytes, "ana");
        assertEquals(1, v1.version());
        assertTrue(v1.active());
        assertEquals("notice-v1.pdf", v1.fileName());
        assertArrayEquals(v1Bytes, privacyNotices.readActiveBytes().orElseThrow());

        GuestPrivacyNotice v2 = privacyNotices.upload("notice-v2.pdf", v2Bytes, "manager1");
        assertEquals(2, v2.version());
        assertTrue(v2.active());
        assertArrayEquals(v2Bytes, privacyNotices.readActiveBytes().orElseThrow());

        // History keeps both versions, newest first, with only v2 active
        List<GuestPrivacyNotice> history = privacyNotices.history();
        assertEquals(2, history.size());
        assertEquals(2, history.get(0).version());
        assertTrue(history.get(0).active());
        assertEquals(1, history.get(1).version());
        assertFalse(history.get(1).active());

        // Both files exist on disk in the sibling documents directory, never under image.upload.directory
        Path publicImagesRoot = Paths.get(uploadDirectory).toAbsolutePath().normalize();
        Path tenantDocsDir = GuestPrivacyNoticeServiceJpaImpl.resolveTenantDocumentsPath(uploadDirectory, tenant);
        assertFalse(tenantDocsDir.startsWith(publicImagesRoot),
                "Privacy notice PDFs must not be stored inside the public /user-images/** directory");

        Path file1 = tenantDocsDir.resolve(v1.storagePath()).normalize();
        Path file2 = tenantDocsDir.resolve(v2.storagePath()).normalize();
        assertTrue(Files.isRegularFile(file1));
        assertTrue(Files.isRegularFile(file2));
        assertNotEquals(file1, file2);

        // Switching back to the generated notice deactivates all rows while keeping files and history intact
        privacyNotices.useGeneratedNotice();
        assertTrue(privacyNotices.findActive().isEmpty());
        assertTrue(privacyNotices.readActiveBytes().isEmpty());
        List<GuestPrivacyNotice> afterRevert = privacyNotices.history();
        assertEquals(2, afterRevert.size());
        assertTrue(afterRevert.stream().noneMatch(GuestPrivacyNotice::active));
        assertTrue(Files.isRegularFile(file1));
        assertTrue(Files.isRegularFile(file2));
    }

    @Test
    void cleanKeepsPrivacyNoticesAndBundleImportAddsNewVersionOnlyWhenSha256Differs() {
        String sourceTenant = "privacysource";
        String destTenant = "privacydest";
        provisioner.provisionTenant(sourceTenant);
        provisioner.provisionTenant(destTenant);

        byte[] pdfA = "%PDF-1.4\nPrivacy Notice A\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
        byte[] pdfB = "%PDF-1.4\nPrivacy Notice B\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

        // Upload pdfA in destTenant (v1)
        TenantContext.setCurrentTenant(destTenant);
        privacyNotices.upload("dest-a.pdf", pdfA, "owner");
        assertEquals(1, privacyNotices.history().size());

        // Clean destTenant -> keeps v1 and its file
        backupRestore.cleanTenantData(destTenant);
        TenantContext.setCurrentTenant(destTenant);
        assertEquals(1, privacyNotices.history().size());
        assertTrue(privacyNotices.findActive().isPresent());
        assertArrayEquals(pdfA, privacyNotices.readActiveBytes().orElseThrow());

        // Export production bundle from sourceTenant with the SAME pdfA -> importing into destTenant does NOT add v2
        TenantContext.setCurrentTenant(sourceTenant);
        privacyNotices.upload("source-a.pdf", pdfA, "owner");
        ByteArrayOutputStream bundleSame = new ByteArrayOutputStream();
        backupRestore.exportProductionBundle(sourceTenant, bundleSame);

        backupRestore.importProductionBundle(destTenant, new ByteArrayInputStream(bundleSame.toByteArray()));
        TenantContext.setCurrentTenant(destTenant);
        assertEquals(1, privacyNotices.history().size());
        assertEquals(1, privacyNotices.findActive().orElseThrow().version());

        // Upload pdfB in sourceTenant (different SHA-256) and export production bundle -> importing into destTenant adds v2 and makes it active
        TenantContext.setCurrentTenant(sourceTenant);
        privacyNotices.upload("source-b.pdf", pdfB, "owner");
        ByteArrayOutputStream bundleDiff = new ByteArrayOutputStream();
        backupRestore.exportProductionBundle(sourceTenant, bundleDiff);

        backupRestore.importProductionBundle(destTenant, new ByteArrayInputStream(bundleDiff.toByteArray()));
        TenantContext.setCurrentTenant(destTenant);
        List<GuestPrivacyNotice> destHistory = privacyNotices.history();
        assertEquals(2, destHistory.size());
        assertEquals(2, destHistory.get(0).version());
        assertTrue(destHistory.get(0).active());
        assertEquals(1, destHistory.get(1).version());
        assertFalse(destHistory.get(1).active());
        assertArrayEquals(pdfB, privacyNotices.readActiveBytes().orElseThrow());
    }
}
