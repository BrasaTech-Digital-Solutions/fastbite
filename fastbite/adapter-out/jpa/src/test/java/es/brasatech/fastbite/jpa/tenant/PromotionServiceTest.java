package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PromotionException;
import es.brasatech.fastbite.application.platform.PromotionPort;
import es.brasatech.fastbite.application.platform.PromotionService;
import es.brasatech.fastbite.application.platform.PromotionService.PromotionResult;
import es.brasatech.fastbite.application.platform.PromotionService.UndoResult;
import es.brasatech.fastbite.application.platform.SandboxLinkPort;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort.PromotionPreview;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.jpa.TestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = TestConfig.class)
class PromotionServiceTest {

    @Autowired
    private PromotionJdbcAdapter promotionJdbcAdapter;

    @TempDir
    Path tempStorage;
    private Path promoStorage;

    private SandboxLinkPort sandboxLinkPort;
    private TenantBackupRestorePort backupRestorePort;
    private TenantLocationService locationService;
    private PromotionService promotionService;

    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneId.of("UTC"));

    private String tenant;

    @BeforeEach
    void setUp() {
        tenant = "prm" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        sandboxLinkPort = mock(SandboxLinkPort.class);
        backupRestorePort = mock(TenantBackupRestorePort.class);
        locationService = mock(TenantLocationService.class);

        when(locationService.isOwnerOf(anyString(), anyString())).thenReturn(true);
        when(locationService.isClosed(anyString())).thenReturn(false);

        promoStorage = tempStorage.resolve("promotions");
        promotionService = new PromotionService(
                promotionJdbcAdapter,
                sandboxLinkPort,
                backupRestorePort,
                locationService,
                new PlatformEnvironment("production"),
                clock,
                tempStorage.resolve("images").toString(),
                promoStorage.toString()
        );
    }

    @Test
    void refusesToStartWhenPromotionsRootIsInsideImageUploadDir() {
        Path imageDir = tempStorage.resolve("images");
        Path promoDir = imageDir.resolve("promotions");
        assertThatThrownBy(() -> new PromotionService(
                promotionJdbcAdapter,
                sandboxLinkPort,
                backupRestorePort,
                locationService,
                new PlatformEnvironment("production"),
                clock,
                imageDir.toString(),
                promoDir.toString()
        )).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("must not be inside");
    }

    @Test
    void defaultsPromotionsRootToSiblingOfImageUploadDir() {
        Path imageDir = tempStorage.resolve("images");
        PromotionService svc = new PromotionService(
                promotionJdbcAdapter,
                sandboxLinkPort,
                backupRestorePort,
                locationService,
                new PlatformEnvironment("production"),
                clock,
                imageDir.toString(),
                ""
        );
        assertThat(svc).isNotNull();
    }

    private PromotionPreview fakePreview() {
        return new PromotionPreview(
                null,
                PromotionPreview.EntityDiff.empty(),
                PromotionPreview.EntityDiff.empty(),
                PromotionPreview.EntityDiff.empty(),
                PromotionPreview.EntityDiff.empty(),
                PromotionPreview.EntityDiff.empty(),
                new PromotionPreview.ThemeDiff(null, null),
                true,
                List.of(),
                2,
                0L
        );
    }

    @Test
    void previewConfirmAndUndoLifecycle() throws Exception {
        when(sandboxLinkPort.streamBundle(anyString(), anyString(), anyLong()))
                .thenAnswer(inv -> new ByteArrayInputStream("bundle-zip-content".getBytes()));
        when(backupRestorePort.previewPromotion(eq(tenant), any())).thenReturn(fakePreview());

        // 1. Preview
        PromotionPreview result = promotionService.preview(tenant, "ana");
        assertThat(result.token()).isNotNull().isNotBlank();
        assertThat(result.pricesOverwritten()).isEqualTo(2);

        // Verify pending zip created
        Path tenantDir = promoStorage.resolve(tenant);
        assertThat(Files.list(tenantDir).filter(p -> p.getFileName().toString().startsWith("pending-")).count()).isEqualTo(1);

        // 2. Confirm
        doAnswer(inv -> {
            OutputStream out = inv.getArgument(1);
            out.write("pre-promotion-snapshot-content".getBytes());
            return null;
        }).when(backupRestorePort).exportProductionBundle(eq(tenant), any());

        PromotionResult confirmResult = promotionService.confirm(tenant, "ana", result.token());
        assertThat(confirmResult.snapshotName()).startsWith("snapshot-");

        // Pending zip must be deleted after apply
        assertThat(Files.list(tenantDir).filter(p -> p.getFileName().toString().startsWith("pending-")).count()).isZero();
        // Pre-promotion snapshot exists on disk
        Path snapshotFile = tenantDir.resolve(confirmResult.snapshotName());
        assertThat(Files.exists(snapshotFile)).isTrue();

        // 3. Undo
        UndoResult undoResult = promotionService.undo(tenant, "ana");
        assertThat(undoResult.snapshotName()).isEqualTo(confirmResult.snapshotName());
        verify(backupRestorePort).restorePromotionSnapshot(eq(tenant), any());

        // 4. Second undo fails because already undone
        assertThatThrownBy(() -> promotionService.undo(tenant, "ana"))
                .isInstanceOf(PromotionException.class)
                .matches(e -> ((PromotionException) e).code().equals("promotion.undo.none"));
    }

    @Test
    void onlyLatestPreviewIsValid() throws Exception {
        when(sandboxLinkPort.streamBundle(anyString(), anyString(), anyLong()))
                .thenAnswer(inv -> new ByteArrayInputStream("bundle-data".getBytes()));
        when(backupRestorePort.previewPromotion(eq(tenant), any())).thenReturn(fakePreview());

        // Preview A
        PromotionPreview previewA = promotionService.preview(tenant, "ana");
        // Preview B
        PromotionPreview previewB = promotionService.preview(tenant, "ana");

        doAnswer(inv -> {
            OutputStream out = inv.getArgument(1);
            out.write("snapshot".getBytes());
            return null;
        }).when(backupRestorePort).exportProductionBundle(eq(tenant), any());

        // Confirm A must be refused because superseded by B
        assertThatThrownBy(() -> promotionService.confirm(tenant, "ana", previewA.token()))
                .isInstanceOf(PromotionException.class)
                .matches(e -> ((PromotionException) e).code().equals("promotion.token.invalid") && ((PromotionException) e).statusCode() == 409);

        // Confirm B must succeed
        PromotionResult resultB = promotionService.confirm(tenant, "ana", previewB.token());
        assertThat(resultB).isNotNull();
    }

    @Test
    void twoThreadsConfirmingSameTokenExactlyOneWins() throws Exception {
        when(sandboxLinkPort.streamBundle(anyString(), anyString(), anyLong()))
                .thenAnswer(inv -> new ByteArrayInputStream("bundle-zip-content".getBytes()));
        when(backupRestorePort.previewPromotion(eq(tenant), any())).thenReturn(fakePreview());

        PromotionPreview preview = promotionService.preview(tenant, "ana");
        String token = preview.token();

        doAnswer(inv -> {
            OutputStream out = inv.getArgument(1);
            out.write("snapshot".getBytes());
            return null;
        }).when(backupRestorePort).exportProductionBundle(eq(tenant), any());

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    promotionService.confirm(tenant, "ana", token);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        assertThat(successCount.get()).as("Exactly one thread must succeed in confirming the token").isEqualTo(1);
        assertThat(failureCount.get()).as("The competing thread must fail").isEqualTo(1);
    }

    @Test
    void confirmFailureOnVersionMismatchDeletesBothFilesAndMarksFailed() throws Exception {
        when(sandboxLinkPort.streamBundle(anyString(), anyString(), anyLong()))
                .thenAnswer(inv -> new ByteArrayInputStream("bundle-data".getBytes()));
        when(backupRestorePort.previewPromotion(eq(tenant), any())).thenReturn(fakePreview());

        PromotionPreview preview = promotionService.preview(tenant, "ana");

        doAnswer(inv -> {
            OutputStream out = inv.getArgument(1);
            out.write("snapshot-content".getBytes());
            return null;
        }).when(backupRestorePort).exportProductionBundle(eq(tenant), any());

        doThrow(new TenantBackupRestorePort.VersionMismatch("old-version", "new-version"))
                .when(backupRestorePort).importProductionBundle(eq(tenant), any());

        assertThatThrownBy(() -> promotionService.confirm(tenant, "ana", preview.token()))
                .isInstanceOf(PromotionException.class)
                .matches(e -> ((PromotionException) e).code().equals("promotion.sandbox.other-version") && ((PromotionException) e).statusCode() == 409);

        Path tenantDir = promoStorage.resolve(tenant);
        // All zip files (pending and snapshot) must be deleted from disk
        assertThat(Files.list(tenantDir).count()).isZero();
    }

    @Test
    void undoOlderThanSevenDaysIsRefused() {
        String id = UUID.randomUUID().toString();
        Instant eightDaysAgo = NOW.minus(Duration.ofDays(8));
        PromotionPort.Entry oldEntry = new PromotionPort.Entry(
                id, tenant, "ana", PromotionPort.State.PREVIEWED, "hash-old", "b", null, null, "sum",
                eightDaysAgo, eightDaysAgo.plus(Duration.ofMinutes(15)), null, null
        );
        promotionJdbcAdapter.savePreview(oldEntry);

        Path snapshotPath = promoStorage.resolve(tenant).resolve("snapshot-" + id + ".zip");
        try {
            Files.createDirectories(snapshotPath.getParent());
            Files.writeString(snapshotPath, "dummy");
        } catch (Exception ignored) {}

        promotionJdbcAdapter.markApplied(id, snapshotPath.toString(), snapshotPath.getFileName().toString(), eightDaysAgo);

        assertThatThrownBy(() -> promotionService.undo(tenant, "ana"))
                .isInstanceOf(PromotionException.class)
                .matches(e -> ((PromotionException) e).code().equals("promotion.undo.expired"));
    }

    @Test
    void applyingRowWithLiveLeaseSurvivesSweepWhileStaleOrFreeLeaseIsFailed() throws Exception {
        String tenantLive = "live" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String tenantStale = "stale" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        Path liveDir = promoStorage.resolve(tenantLive);
        Path staleDir = promoStorage.resolve(tenantStale);
        Files.createDirectories(liveDir);
        Files.createDirectories(staleDir);

        // Live tenant has an APPLYING row with created_at 11 min ago, and an active lease
        Path liveBundle = liveDir.resolve("pending-live.zip");
        Path liveSnap = liveDir.resolve("snapshot-live.zip");
        Files.writeString(liveBundle, "live-bundle");
        Files.writeString(liveSnap, "live-snap");
        String liveId = UUID.randomUUID().toString();
        PromotionPort.Entry liveEntry = new PromotionPort.Entry(
                liveId, tenantLive, "ana", PromotionPort.State.APPLYING, "h-live-" + UUID.randomUUID(),
                liveBundle.toString(), liveSnap.toString(), "snapshot-live.zip", "s",
                NOW.minus(Duration.ofMinutes(11)), NOW.plus(Duration.ofMinutes(15)), null, null);
        promotionJdbcAdapter.savePreview(liveEntry);
        // Active lease claimed 2 minutes ago
        promotionJdbcAdapter.claimLease(tenantLive, NOW.minus(Duration.ofMinutes(2)), Duration.ofMinutes(10));

        // Stale tenant has an APPLYING row with created_at 11 min ago, but no active lease
        Path staleBundle = staleDir.resolve("pending-stale.zip");
        Path staleSnap = staleDir.resolve("snapshot-stale.zip");
        Files.writeString(staleBundle, "stale-bundle");
        Files.writeString(staleSnap, "stale-snap");
        String staleId = UUID.randomUUID().toString();
        PromotionPort.Entry staleEntry = new PromotionPort.Entry(
                staleId, tenantStale, "ana", PromotionPort.State.APPLYING, "h-stale-" + UUID.randomUUID(),
                staleBundle.toString(), staleSnap.toString(), "snapshot-stale.zip", "s",
                NOW.minus(Duration.ofMinutes(11)), NOW.plus(Duration.ofMinutes(15)), null, null);
        promotionJdbcAdapter.savePreview(staleEntry);
        // Lease was claimed 15 minutes ago (expired)
        promotionJdbcAdapter.claimLease(tenantStale, NOW.minus(Duration.ofMinutes(15)), Duration.ofMinutes(10));

        promotionService.sweep();

        // Live tenant's row and files survive
        assertThat(Files.exists(liveBundle)).isTrue();
        assertThat(Files.exists(liveSnap)).isTrue();
        PromotionPort.Entry liveAfter = promotionJdbcAdapter.findById(liveId).orElseThrow();
        assertThat(liveAfter.state()).isEqualTo(PromotionPort.State.APPLYING);

        // Stale tenant's files deleted and row marked FAILED
        assertThat(Files.exists(staleBundle)).isFalse();
        assertThat(Files.exists(staleSnap)).isFalse();
        PromotionPort.Entry staleAfter = promotionJdbcAdapter.findById(staleId).orElseThrow();
        assertThat(staleAfter.state()).isEqualTo(PromotionPort.State.FAILED);
    }

    @Test
    void sweepExpiresOldPreviewsAndStaleApplyingAndDeletesFiles() throws Exception {
        Path tenantDir = promoStorage.resolve(tenant);
        Files.createDirectories(tenantDir);

        // Expired preview file
        Path expiredPending = tenantDir.resolve("pending-expired.zip");
        Files.writeString(expiredPending, "pending");
        PromotionPort.Entry expiredEntry = new PromotionPort.Entry(
                UUID.randomUUID().toString(), tenant, "ana", PromotionPort.State.PREVIEWED, "h-exp",
                expiredPending.toString(), null, null, "s",
                NOW.minus(Duration.ofMinutes(30)), NOW.minus(Duration.ofMinutes(15)), null, null);
        promotionJdbcAdapter.savePreview(expiredEntry);

        // Stale applying file
        Path staleBundle = tenantDir.resolve("pending-stale.zip");
        Files.writeString(staleBundle, "stale-bundle");
        Path staleSnap = tenantDir.resolve("snapshot-stale.zip");
        Files.writeString(staleSnap, "stale-snap");
        String staleId = UUID.randomUUID().toString();
        PromotionPort.Entry staleEntry = new PromotionPort.Entry(
                staleId, tenant, "ana", PromotionPort.State.APPLYING, "h-stale",
                staleBundle.toString(), staleSnap.toString(), "snapshot-stale.zip", "s",
                NOW.minus(Duration.ofMinutes(20)), NOW.plus(Duration.ofMinutes(15)), null, null);
        promotionJdbcAdapter.savePreview(staleEntry);

        int swept = promotionService.sweep();
        assertThat(swept).isGreaterThanOrEqualTo(2);

        // Files on disk must be gone
        assertThat(Files.exists(expiredPending)).isFalse();
        assertThat(Files.exists(staleBundle)).isFalse();
        assertThat(Files.exists(staleSnap)).isFalse();
    }

    @Test
    void snapshotRetentionPrunesOldFilesAndRows() throws Exception {
        Path tenantDir = promoStorage.resolve(tenant);
        Files.createDirectories(tenantDir);

        List<Path> createdSnapshots = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String id = UUID.randomUUID().toString();
            Instant t = NOW.minus(Duration.ofHours(5 - i));
            PromotionPort.Entry e = new PromotionPort.Entry(
                    id, tenant, "ana", PromotionPort.State.PREVIEWED, "hash-" + id, "b" + i, null, null, "s" + i,
                    t, t.plus(Duration.ofMinutes(15)), null, null
            );
            promotionJdbcAdapter.savePreview(e);
            Path snap = tenantDir.resolve("snapshot-" + id + ".zip");
            Files.writeString(snap, "snap-content-" + i);
            createdSnapshots.add(snap);
            promotionJdbcAdapter.markApplied(id, snap.toString(), snap.getFileName().toString(), t.plusSeconds(5));
        }

        assertThat(promotionJdbcAdapter.findAppliedOrUndoneBeyond(tenant, 3)).hasSize(2);

        when(sandboxLinkPort.streamBundle(eq(tenant), eq("ana"), anyLong()))
                .thenReturn(new ByteArrayInputStream("bundle".getBytes()));
        when(backupRestorePort.previewPromotion(eq(tenant), any())).thenReturn(fakePreview());

        PromotionPreview preview = promotionService.preview(tenant, "ana");
        promotionService.confirm(tenant, "ana", preview.token());

        assertThat(promotionJdbcAdapter.findAppliedOrUndoneBeyond(tenant, 3)).isEmpty();
    }
}
