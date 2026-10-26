package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantBackupDownloadTest {
    private final TenantBackupRestorePort backups = mock(TenantBackupRestorePort.class);
    private final TenantMaintenanceController controller = new TenantMaintenanceController(backups,
            new es.brasatech.fastbite.application.platform.PlatformLink(
                    new es.brasatech.fastbite.application.platform.PlatformEnvironment("sandbox"), "", "", "", false),
            mock(org.springframework.context.MessageSource.class));

    @Test void failedExportReturns500WithoutAPartialDownload() throws Exception {
        doAnswer(call -> {
            OutputStream output = call.getArgument(1);
            output.write(new byte[20_000]); // Would previously overflow the servlet buffer and commit HTTP 200.
            throw new IllegalStateException("Serialization failed");
        }).when(backups).exportBackup(eq("burger"), any());
        var response = new MockHttpServletResponse();
        controller.downloadBackup("burger", response);
        assertEquals(500, response.getStatus());
        assertEquals(0, response.getContentAsByteArray().length);
        assertNull(response.getHeader("Content-Disposition"));
    }

    @Test void completedArchiveIsDownloadedWithItsLengthAndFilename() throws Exception {
        byte[] bytes = "complete-test-archive".getBytes(StandardCharsets.UTF_8);
        doAnswer(call -> { ((OutputStream) call.getArgument(1)).write(bytes); return null; })
                .when(backups).exportBackup(eq("burger"), any());
        var response = new MockHttpServletResponse();
        controller.downloadBackup("burger", response);
        assertEquals(200, response.getStatus());
        assertEquals("application/zip", response.getContentType());
        assertEquals("attachment; filename=burger_backup.zip", response.getHeader("Content-Disposition"));
        assertArrayEquals(bytes, response.getContentAsByteArray());
        assertEquals(bytes.length, response.getContentLength());
    }
}
