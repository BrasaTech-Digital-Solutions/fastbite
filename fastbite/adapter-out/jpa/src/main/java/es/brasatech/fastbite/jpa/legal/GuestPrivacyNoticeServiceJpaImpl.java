package es.brasatech.fastbite.jpa.legal;

import es.brasatech.fastbite.application.settings.GuestPrivacyNoticeService;
import es.brasatech.fastbite.domain.settings.GuestPrivacyNotice;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class GuestPrivacyNoticeServiceJpaImpl implements GuestPrivacyNoticeService {

    @PersistenceContext
    private EntityManager entityManager;

    private final PlatformTransactionManager transactionManager;
    private final String uploadDirectory;
    private final Clock clock;

    @Autowired
    public GuestPrivacyNoticeServiceJpaImpl(
            PlatformTransactionManager transactionManager,
            @Value("${image.upload.directory:uploads/images}") String uploadDirectory,
            Clock clock) {
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.uploadDirectory = Objects.requireNonNull(uploadDirectory, "uploadDirectory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Resolves the sibling documents root next to the public images directory so
     * {@code /user-images/**} never serves privacy PDFs while the {@code /storage}
     * volume/GCS mount still covers them.
     */
    public static Path resolveDocumentsRoot(String uploadDirectory) {
        Path uploadRoot = Paths.get(uploadDirectory).toAbsolutePath().normalize();
        Path fileName = uploadRoot.getFileName();
        if (fileName != null && "images".equalsIgnoreCase(fileName.toString())) {
            return uploadRoot.resolveSibling("documents").normalize();
        }
        return uploadRoot.resolveSibling((fileName != null ? fileName.toString() : "storage") + "-documents").normalize();
    }

    public static Path resolveTenantDocumentsPath(String uploadDirectory, String tenantId) {
        if (tenantId == null || !tenantId.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid tenant identifier for privacy notice storage");
        }
        Path root = resolveDocumentsRoot(uploadDirectory);
        Path path = root.resolve(tenantId).normalize();
        for (Path parent = path; parent != null; parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) {
                throw new IllegalArgumentException("Documents path contains a symbolic link");
            }
        }
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Invalid tenant documents path");
        }
        return path;
    }

    @Override
    public Optional<GuestPrivacyNotice> findActive() {
        currentTenant();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);
        List<GuestPrivacyNoticeEntity> rows = tx.execute(status -> entityManager.createQuery(
                        "SELECT n FROM GuestPrivacyNotice n WHERE n.active = true ORDER BY n.version DESC",
                        GuestPrivacyNoticeEntity.class)
                .setMaxResults(1)
                .getResultList());
        return (rows == null ? List.<GuestPrivacyNoticeEntity>of() : rows)
                .stream().findFirst().map(GuestPrivacyNoticeServiceJpaImpl::toDomain);
    }

    @Override
    public List<GuestPrivacyNotice> history() {
        currentTenant();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);
        List<GuestPrivacyNoticeEntity> rows = tx.execute(status -> entityManager.createQuery(
                        "SELECT n FROM GuestPrivacyNotice n ORDER BY n.version DESC",
                        GuestPrivacyNoticeEntity.class)
                .getResultList());
        return (rows == null ? List.<GuestPrivacyNoticeEntity>of() : rows)
                .stream()
                .map(GuestPrivacyNoticeServiceJpaImpl::toDomain)
                .toList();
    }

    @Override
    public Optional<byte[]> readActiveBytes() {
        Optional<GuestPrivacyNotice> active = findActive();
        if (active.isEmpty()) {
            return Optional.empty();
        }
        String tenantId = currentTenant();
        Path tenantDir = resolveTenantDocumentsPath(uploadDirectory, tenantId);
        Path file = tenantDir.resolve(active.get().storagePath()).normalize();
        if (!file.startsWith(tenantDir) || !Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read active privacy notice PDF", e);
        }
    }

    @Override
    public GuestPrivacyNotice upload(String originalFileName, byte[] bytes, String uploadedBy) {
        GuestPrivacyNoticeService.validatePdf(bytes);
        String fileName = GuestPrivacyNoticeService.sanitizeFileName(originalFileName);
        String sha256 = GuestPrivacyNoticeService.sha256Hex(bytes);
        String uploader = (uploadedBy == null || uploadedBy.isBlank())
                ? "owner"
                : (uploadedBy.length() > 100 ? uploadedBy.substring(0, 100) : uploadedBy.strip());
        String tenantId = currentTenant();
        Path tenantDir = resolveTenantDocumentsPath(uploadDirectory, tenantId);
        Instant now = clock.instant();

        // Compute version as max + 1 inside the transaction; retry once on primary key conflict.
        for (int attempt = 1; attempt <= 2; attempt++) {
            AtomicReference<Path> writtenFile = new AtomicReference<>();
            try {
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                GuestPrivacyNotice saved = tx.execute(status -> {
                    int nextVersion = ((Number) entityManager.createNativeQuery(
                                    "SELECT COALESCE(MAX(version), 0) + 1 FROM guest_privacy_notices")
                            .getSingleResult()).intValue();
                    String relativePath = "privacy/privacy-v" + nextVersion + "-" + now.toEpochMilli() + ".pdf";
                    Path targetFile = tenantDir.resolve(relativePath).normalize();
                    if (Files.exists(targetFile)) {
                        relativePath = "privacy/privacy-v" + nextVersion + "-" + now.toEpochMilli()
                                + "-" + Math.abs(System.nanoTime() % 100_000) + ".pdf";
                        targetFile = tenantDir.resolve(relativePath).normalize();
                    }
                    if (!targetFile.startsWith(tenantDir)) {
                        throw new IllegalStateException("Invalid privacy notice target path");
                    }
                    try {
                        Files.createDirectories(targetFile.getParent());
                        Files.write(targetFile, bytes, StandardOpenOption.CREATE_NEW);
                        writtenFile.set(targetFile);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to store privacy notice PDF", e);
                    }

                    entityManager.createNativeQuery("UPDATE guest_privacy_notices SET active = FALSE WHERE active = TRUE")
                            .executeUpdate();

                    GuestPrivacyNoticeEntity entity = new GuestPrivacyNoticeEntity();
                    entity.setVersion(nextVersion);
                    entity.setFileName(fileName);
                    entity.setStoragePath(relativePath);
                    entity.setFileSize(bytes.length);
                    entity.setSha256(sha256);
                    entity.setUploadedBy(uploader);
                    entity.setUploadedAt(now);
                    entity.setActive(true);
                    entityManager.persist(entity);
                    entityManager.flush();
                    return toDomain(entity);
                });
                return Objects.requireNonNull(saved);
            } catch (DataIntegrityViolationException | PersistenceException e) {
                Path created = writtenFile.get();
                if (created != null) {
                    try {
                        Files.deleteIfExists(created);
                    } catch (IOException ignored) {
                    }
                }
                if (attempt == 2) {
                    throw e;
                }
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    @Override
    public void useGeneratedNotice() {
        currentTenant();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> entityManager
                .createNativeQuery("UPDATE guest_privacy_notices SET active = FALSE WHERE active = TRUE")
                .executeUpdate());
    }

    public static GuestPrivacyNotice toDomain(GuestPrivacyNoticeEntity entity) {
        return new GuestPrivacyNotice(
                entity.getVersion(),
                entity.getFileName(),
                entity.getStoragePath(),
                entity.getFileSize(),
                entity.getSha256(),
                entity.getUploadedBy(),
                entity.getUploadedAt(),
                entity.isActive());
    }

    private static String currentTenant() {
        String tenant = TenantContext.getCurrentTenant();
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalStateException("No active restaurant tenant in context");
        }
        return tenant.strip();
    }
}
