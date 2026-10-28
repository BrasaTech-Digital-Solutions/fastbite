package es.brasatech.fastbite.application.platform;

import es.brasatech.fastbite.application.platform.SandboxLinkPort.BundleStreamException;
import es.brasatech.fastbite.application.platform.SandboxLinkPort.Reason;
import es.brasatech.fastbite.application.platform.SandboxLinkPort.Unavailable;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort.PromotionPreview;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

@Service
public class PromotionService {

    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(10);
    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);
    private static final Duration UNDO_WINDOW = Duration.ofDays(7);
    private static final int SNAPSHOTS_TO_KEEP = 3;

    private final PromotionPort promotionPort;
    private final SandboxLinkPort sandboxLinkPort;
    private final TenantBackupRestorePort backupRestorePort;
    private final TenantLocationService locationService;
    private final PlatformEnvironment environment;
    private final Clock clock;
    private final Path promotionsRoot;
    private final SecureRandom secureRandom = new SecureRandom();

    public record PromotionResult(String id, String summary, String snapshotName) {}
    public record UndoResult(String id, String snapshotName) {}
    public record UndoablePromotionInfo(String id, Instant appliedAt, String summary) {}

    public PromotionService(
            PromotionPort promotionPort,
            SandboxLinkPort sandboxLinkPort,
            TenantBackupRestorePort backupRestorePort,
            TenantLocationService locationService,
            PlatformEnvironment environment,
            Clock clock,
            @Value("${fastbite.promotions.directory:${image.upload.directory}/promotions}") String storageDir) {
        this.promotionPort = promotionPort;
        this.sandboxLinkPort = sandboxLinkPort;
        this.backupRestorePort = backupRestorePort;
        this.locationService = locationService;
        this.environment = environment;
        this.clock = clock;
        this.promotionsRoot = Paths.get(storageDir).toAbsolutePath().normalize();
    }

    public PromotionPreview preview(String tenantId, String ownerUsername) {
        verifyProduction();
        verifyOwnershipAndOpen(tenantId, ownerUsername);

        Instant now = clock.instant();
        OptionalLong claim = promotionPort.claimLease(tenantId, now, LEASE_DURATION);
        if (claim.isEmpty()) {
            throw new PromotionException("promotion.busy", 409, "Another promotion is already in progress for this location");
        }
        long claimedAt = claim.getAsLong();

        try {
            // Invalidate other previews for this tenant and prune their pending files
            List<String> oldFiles = promotionPort.expireOtherPreviews(tenantId);
            for (String oldFile : oldFiles) {
                try {
                    Files.deleteIfExists(Paths.get(oldFile));
                } catch (IOException ignored) {}
            }

            Path tenantDir = resolveTenantPromotionsDir(tenantId);
            Files.createDirectories(tenantDir);

            String promotionId = UUID.randomUUID().toString();
            Path pendingBundlePath = tenantDir.resolve("pending-" + promotionId + ".zip");

            try (InputStream in = sandboxLinkPort.streamBundle(tenantId, ownerUsername, SandboxLinkPort.DEFAULT_MAX_BUNDLE_BYTES);
                 OutputStream out = Files.newOutputStream(pendingBundlePath)) {
                in.transferTo(out);
            } catch (Unavailable e) {
                Files.deleteIfExists(pendingBundlePath);
                throw mapSandboxUnavailable(e);
            } catch (BundleStreamException e) {
                Files.deleteIfExists(pendingBundlePath);
                throw mapBundleStreamException(e);
            } catch (IOException e) {
                Files.deleteIfExists(pendingBundlePath);
                throw new PromotionException("promotion.download.incomplete", 502, "The download was incomplete, try again", e);
            }

            PromotionPreview preview;
            try {
                preview = backupRestorePort.previewPromotion(tenantId, pendingBundlePath);
            } catch (TenantBackupRestorePort.VersionMismatch e) {
                Files.deleteIfExists(pendingBundlePath);
                throw new PromotionException("promotion.sandbox.other-version", 409, e.getMessage(), e);
            } catch (TenantBackupRestorePort.UnreadableBundle e) {
                Files.deleteIfExists(pendingBundlePath);
                throw new PromotionException("promotion.download.incomplete", 502, e.getMessage(), e);
            } catch (Exception e) {
                Files.deleteIfExists(pendingBundlePath);
                throw new PromotionException("promotion.download.incomplete", 502, e.getMessage(), e);
            }

            byte[] tokenBytes = new byte[32];
            secureRandom.nextBytes(tokenBytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
            String tokenHash = sha256Hex(token);

            Instant expiresAt = now.plus(TOKEN_TTL);
            String summary = String.format("%d products (+%d/-%d/~%d), %d categories, %d tables",
                    preview.products().added() + preview.products().unchanged() + preview.products().changed(),
                    preview.products().added(), preview.products().removed(), preview.products().changed(),
                    preview.categories().added() + preview.categories().unchanged() + preview.categories().changed(),
                    preview.tables().added() + preview.tables().unchanged() + preview.tables().changed());

            PromotionPort.Entry entry = new PromotionPort.Entry(
                    promotionId,
                    tenantId,
                    ownerUsername,
                    PromotionPort.State.PREVIEWED,
                    tokenHash,
                    pendingBundlePath.toAbsolutePath().toString(),
                    null,
                    null,
                    summary,
                    now,
                    expiresAt,
                    null,
                    null
            );
            promotionPort.savePreview(entry);

            return preview.withTokenAndExpiry(token, expiresAt.toEpochMilli());
        } catch (IOException e) {
            throw new PromotionException("promotion.failed", 500, "Failed to prepare promotion storage: " + e.getMessage(), e);
        } finally {
            promotionPort.releaseLease(tenantId, claimedAt);
        }
    }

    public PromotionResult confirm(String tenantId, String ownerUsername, String token) {
        verifyProduction();
        verifyOwnershipAndOpen(tenantId, ownerUsername);

        if (token == null || token.isBlank()) {
            throw new PromotionException("promotion.token.invalid", 400, "Missing promotion token");
        }

        Instant now = clock.instant();
        OptionalLong claim = promotionPort.claimLease(tenantId, now, LEASE_DURATION);
        if (claim.isEmpty()) {
            throw new PromotionException("promotion.busy", 409, "Another promotion is already in progress for this location");
        }
        long claimedAt = claim.getAsLong();

        try {
            String tokenHash = sha256Hex(token);
            boolean consumed = promotionPort.consumeToken(tokenHash, tenantId, ownerUsername, now);
            if (!consumed) {
                throw new PromotionException("promotion.token.invalid", 409, "Invalid, expired, or already used promotion token");
            }

            PromotionPort.Entry entry = promotionPort.findByTokenHash(tokenHash)
                    .orElseThrow(() -> new PromotionException("promotion.token.invalid", 409, "Promotion entry not found"));

            Path pendingBundlePath = Paths.get(entry.bundlePath());
            if (!Files.exists(pendingBundlePath)) {
                promotionPort.markFailed(entry.id());
                throw new PromotionException("promotion.download.incomplete", 502, "Pending promotion bundle file was not found on disk");
            }

            Path tenantDir = resolveTenantPromotionsDir(tenantId);
            String snapshotName = "snapshot-" + entry.id() + ".zip";
            Path snapshotPath = tenantDir.resolve(snapshotName);

            try (OutputStream out = Files.newOutputStream(snapshotPath)) {
                backupRestorePort.exportProductionBundle(tenantId, out);
            } catch (Exception e) {
                promotionPort.markFailed(entry.id());
                try { Files.deleteIfExists(snapshotPath); } catch (IOException ignored) {}
                try { Files.deleteIfExists(pendingBundlePath); } catch (IOException ignored) {}
                throw new PromotionException("promotion.failed", 500, "Failed to create pre-promotion backup snapshot: " + e.getMessage(), e);
            }

            try (InputStream in = Files.newInputStream(pendingBundlePath)) {
                backupRestorePort.importProductionBundle(tenantId, in);
            } catch (TenantBackupRestorePort.VersionMismatch e) {
                promotionPort.markFailed(entry.id());
                try { Files.deleteIfExists(snapshotPath); } catch (IOException ignored) {}
                try { Files.deleteIfExists(pendingBundlePath); } catch (IOException ignored) {}
                throw new PromotionException("promotion.sandbox.other-version", 409, "Version mismatch during promotion: " + e.getMessage(), e);
            } catch (Exception e) {
                promotionPort.markFailed(entry.id());
                try { Files.deleteIfExists(snapshotPath); } catch (IOException ignored) {}
                try { Files.deleteIfExists(pendingBundlePath); } catch (IOException ignored) {}
                throw new PromotionException("promotion.failed", 500, "Failed to import sandbox bundle: " + e.getMessage(), e);
            }

            promotionPort.markApplied(entry.id(), snapshotPath.toAbsolutePath().toString(), snapshotName, now);

            try {
                Files.deleteIfExists(pendingBundlePath);
            } catch (IOException e) {
                log.warn("Could not delete pending bundle file {}: {}", pendingBundlePath, e.getMessage());
            }

            pruneSnapshots(tenantId);

            log.info("Promotion applied for location {} by owner {}: summary={}, snapshot={}",
                    tenantId, ownerUsername, entry.summary(), snapshotName);

            return new PromotionResult(entry.id(), entry.summary(), snapshotName);
        } finally {
            promotionPort.releaseLease(tenantId, claimedAt);
        }
    }

    public UndoResult undo(String tenantId, String ownerUsername) {
        verifyProduction();
        verifyOwnershipAndOpen(tenantId, ownerUsername);

        Instant now = clock.instant();
        OptionalLong claim = promotionPort.claimLease(tenantId, now, LEASE_DURATION);
        if (claim.isEmpty()) {
            throw new PromotionException("promotion.busy", 409, "Another promotion is already in progress for this location");
        }
        long claimedAt = claim.getAsLong();

        try {
            Optional<PromotionPort.Entry> latestOpt = promotionPort.findLatestAppliedOrUndone(tenantId);
            if (latestOpt.isEmpty()) {
                throw new PromotionException("promotion.undo.none", 400, "No promotion history found to undo");
            }
            PromotionPort.Entry latest = latestOpt.get();
            if (latest.state() != PromotionPort.State.APPLIED) {
                throw new PromotionException("promotion.undo.none", 400, "The latest promotion has already been undone");
            }
            if (latest.appliedAt() == null || latest.appliedAt().isBefore(now.minus(UNDO_WINDOW))) {
                throw new PromotionException("promotion.undo.expired", 400, "Promotions older than 7 days cannot be undone");
            }
            if (latest.snapshotPath() == null || !Files.exists(Paths.get(latest.snapshotPath()))) {
                throw new PromotionException("promotion.undo.missing", 400, "Pre-promotion snapshot file was not found on disk");
            }

            Path snapshotPath = Paths.get(latest.snapshotPath());
            try (InputStream in = Files.newInputStream(snapshotPath)) {
                backupRestorePort.restorePromotionSnapshot(tenantId, in);
            } catch (Exception e) {
                throw new PromotionException("promotion.failed", 500, "Failed to restore pre-promotion snapshot: " + e.getMessage(), e);
            }

            promotionPort.markUndone(latest.id(), now);

            log.info("Promotion undone for location {} by owner {}: restored snapshot {}",
                    tenantId, ownerUsername, latest.snapshotName());

            return new UndoResult(latest.id(), latest.snapshotName());
        } finally {
            promotionPort.releaseLease(tenantId, claimedAt);
        }
    }

    public int sweep() {
        Instant now = clock.instant();
        List<String> filesToDelete = promotionPort.sweep(now, LEASE_DURATION);
        int deleted = 0;
        for (String file : filesToDelete) {
            try {
                if (Files.deleteIfExists(Paths.get(file))) {
                    deleted++;
                }
            } catch (IOException e) {
                log.warn("Failed to delete swept promotion file {}: {}", file, e.getMessage());
            }
        }
        return deleted;
    }

    public Optional<UndoablePromotionInfo> getUndoablePromotion(String tenantId, String ownerUsername) {
        if (!environment.production()) {
            return Optional.empty();
        }
        if (!locationService.isOwnerOf(ownerUsername, tenantId)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return promotionPort.findLatestAppliedOrUndone(tenantId)
                .filter(entry -> entry.state() == PromotionPort.State.APPLIED)
                .filter(entry -> entry.appliedAt() != null && entry.appliedAt().isAfter(now.minus(UNDO_WINDOW)))
                .filter(entry -> entry.snapshotPath() != null && Files.exists(Paths.get(entry.snapshotPath())))
                .map(entry -> new UndoablePromotionInfo(entry.id(), entry.appliedAt(), entry.summary()));
    }

    private void pruneSnapshots(String tenantId) {
        List<PromotionPort.Entry> toPrune = promotionPort.findAppliedOrUndoneBeyond(tenantId, SNAPSHOTS_TO_KEEP);
        for (PromotionPort.Entry oldEntry : toPrune) {
            boolean fileDeleted = true;
            if (oldEntry.snapshotPath() != null) {
                Path p = Paths.get(oldEntry.snapshotPath());
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Failed to delete old promotion snapshot file {}: {}", p, e.getMessage());
                    fileDeleted = false;
                }
            }
            if (fileDeleted) {
                promotionPort.deleteRow(oldEntry.id());
            }
        }
    }

    private Path resolveTenantPromotionsDir(String tenantId) {
        if (tenantId == null || !tenantId.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid tenant identifier for promotion storage");
        }
        Path path = promotionsRoot.resolve(tenantId).normalize();
        if (!path.startsWith(promotionsRoot) || path.equals(promotionsRoot)) {
            throw new IllegalArgumentException("Invalid promotions storage path");
        }
        return path;
    }

    private void verifyProduction() {
        if (!environment.production()) {
            throw new PromotionException("promotion.production-only", 403, "Promote to production is only available in production");
        }
    }

    private void verifyOwnershipAndOpen(String tenantId, String ownerUsername) {
        if (!locationService.isOwnerOf(ownerUsername, tenantId)) {
            throw new PromotionException("promotion.not-owner", 403, "Not location owner");
        }
        if (locationService.isClosed(tenantId)) {
            throw new PromotionException("promotion.closed", 403, "This location is closed");
        }
    }

    private PromotionException mapSandboxUnavailable(Unavailable e) {
        return mapReason(e.reason(), e);
    }

    private PromotionException mapBundleStreamException(BundleStreamException e) {
        if (e.reason() == Reason.TOO_LARGE) {
            return new PromotionException("promotion.sandbox.too-large", 413, "The sandbox data exceeds the maximum allowed size (200 MiB)", e);
        } else if (e.reason() == Reason.TIMED_OUT) {
            return new PromotionException("promotion.sandbox.timed-out", 504, "Streaming the bundle timed out", e);
        }
        return new PromotionException("promotion.download.incomplete", 502, "The download was incomplete, try again", e);
    }

    private PromotionException mapReason(Reason reason, Throwable cause) {
        if (reason == null) {
            return new PromotionException("promotion.sandbox.unreachable", 502, "The sandbox could not be reached. Please retry in a few minutes.", cause);
        }
        return switch (reason) {
            case UNREACHABLE -> new PromotionException("promotion.sandbox.unreachable", 502, "The sandbox could not be reached. Please retry in a few minutes.", cause);
            case OTHER_VERSION -> new PromotionException("promotion.sandbox.other-version", 409, "The sandbox is running another version of FastBite. Please retry after the update finishes.", cause);
            case NOTHING_TO_PROMOTE -> new PromotionException("promotion.sandbox.empty", 422, "The sandbox has no menu to promote yet. Add dishes in the sandbox first.", cause);
            case TOO_LARGE -> new PromotionException("promotion.sandbox.too-large", 413, "The sandbox data exceeds the maximum allowed size (200 MiB).", cause);
            case TIMED_OUT -> new PromotionException("promotion.sandbox.timed-out", 504, "The connection to the sandbox timed out. Please retry in a few minutes.", cause);
            case REFUSED -> new PromotionException("promotion.sandbox.refused", 403, "The promotion request was refused by the sandbox.", cause);
        };
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
