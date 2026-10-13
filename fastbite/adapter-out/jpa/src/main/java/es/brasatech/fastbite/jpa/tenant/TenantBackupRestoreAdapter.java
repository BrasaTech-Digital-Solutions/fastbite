package es.brasatech.fastbite.jpa.tenant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.fiscal.FiscalProfileEntity;
import es.brasatech.fastbite.jpa.customization.*;
import es.brasatech.fastbite.jpa.discount.DiscountRuleEntity;
import es.brasatech.fastbite.jpa.discount.DiscountRuleJpaRepository;
import es.brasatech.fastbite.jpa.group.GroupEntity;
import es.brasatech.fastbite.jpa.group.GroupJpaRepository;
import es.brasatech.fastbite.jpa.order.OrderEntity;
import es.brasatech.fastbite.jpa.order.OrderJpaRepository;
import es.brasatech.fastbite.jpa.payment.PaymentConfigEntity;
import es.brasatech.fastbite.jpa.payment.PaymentConfigJpaRepository;
import es.brasatech.fastbite.jpa.product.ProductEntity;
import es.brasatech.fastbite.jpa.product.ProductJpaRepository;
import es.brasatech.fastbite.jpa.table.TableEntity;
import es.brasatech.fastbite.jpa.table.TableJpaRepository;
import es.brasatech.fastbite.jpa.user.UserEntity;
import es.brasatech.fastbite.jpa.user.UserJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Component
@RequiredArgsConstructor
@Slf4j
public class TenantBackupRestoreAdapter implements TenantBackupRestorePort {

    // Backups use Jackson 2 independently of Spring MVC's Jackson 3 mapper.
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${image.upload.directory}")
    private String uploadDirectory;

    @Value("${fastbite.orders.time-zone:Europe/Madrid}")
    private java.time.ZoneId ordersTimeZone;

    /** Written into every archive; production bundles are only imported by the same version. */
    @Value("${fastbite.version:dev}")
    private String version;

    private final GroupJpaRepository groupJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final CustomizationJpaRepository customizationJpaRepository;
    private final CustomizationOptionJpaRepository customizationOptionJpaRepository;
    private final TableJpaRepository tableJpaRepository;
    private final DiscountRuleJpaRepository discountRuleJpaRepository;
    private final OrderJpaRepository orderJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final PaymentConfigJpaRepository paymentConfigJpaRepository;
    private final DynamicDemoTemplateRegistry dynamicDemoTemplateRegistry;
    private final es.brasatech.fastbite.application.tenant.AppearancePort appearance;

    /** A theme id as the theme catalog names them; anything else in an archive is ignored. */
    private static final java.util.regex.Pattern THEME_ID = java.util.regex.Pattern.compile("[a-z0-9-]{1,40}");

    @Override
    public void exportBackup(String tenantId, OutputStream outputStream) {
        exportArchive(tenantId, outputStream, false);
    }

    @Override
    public void exportProductionBundle(String tenantId, OutputStream outputStream) {
        exportArchive(tenantId, outputStream, true);
    }

    private void exportArchive(String tenantId, OutputStream outputStream, boolean productionBundleOnly) {
        tenantMediaPath(tenantId); // Validate before starting any database/file operation.
        // Kept with the platform, outside the restaurant's schema: read it before entering it
        String locationTheme = appearance.locationTheme(tenantId);
        inTenantTransaction(tenantId, true, () -> exportInTransaction(tenantId, outputStream, productionBundleOnly, locationTheme));
    }

    private void exportInTransaction(String tenantId, OutputStream outputStream, boolean productionBundleOnly,
            String locationTheme) {
        try {
            
            // 1. Gather all database entities
            TenantBackupData backupData = new TenantBackupData();
            backupData.setFormatVersion(3);
            backupData.setAppVersion(version);
            backupData.setProductionBundle(productionBundleOnly);
            // The look chosen in the sandbox goes to production with the menu
            backupData.setLocationTheme(locationTheme);
            backupData.setGroups(groupJpaRepository.findAll());
            backupData.setProducts(productJpaRepository.findAll());
            backupData.setCustomizations(customizationJpaRepository.findAll());
            backupData.setCustomizationOptions(customizationOptionJpaRepository.findAll());
            backupData.setTables(tableJpaRepository.findAll());
            backupData.setDiscountRules(discountRuleJpaRepository.findAll());
            if (productionBundleOnly) {
                // Strictly exclude test orders, staff/test credentials, payment configurations, and order counters
                backupData.setOrders(java.util.List.of());
                backupData.setUsers(java.util.List.of());
                backupData.setPaymentConfigs(java.util.List.of());
                backupData.setOrderCounter(null);
            } else {
                backupData.setOrders(orderJpaRepository.findAll());
                backupData.setUsers(userJpaRepository.findAll().stream()
                        .filter(user -> !isPlatformSchema(tenantId) || isDemoStaff(user)).toList());
                backupData.setPaymentConfigs(paymentConfigJpaRepository.findAll());
                List<?> counters = entityManager.createNativeQuery("SELECT business_day, last_number FROM order_counter WHERE id = 1").getResultList();
                if (!counters.isEmpty()) {
                    Object[] row = (Object[]) counters.getFirst();
                    backupData.setOrderCounter(new OrderCounterBackup(java.time.LocalDate.parse(row[0].toString()), ((Number) row[1]).intValue()));
                }
            }
            backupData.setRestaurantSettings(entityManager.find(es.brasatech.fastbite.jpa.settings.RestaurantSettingsEntity.class, 1));
            // Production bundles carry it too, as a draft the owner confirms during production onboarding
            backupData.setFiscalProfile(entityManager.find(FiscalProfileEntity.class, 1));


            // 2. Write to ZIP
            try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {
                // Entry 1: Data JSON
                ZipEntry dataEntry = new ZipEntry("data.json");
                zos.putNextEntry(dataEntry);
                objectMapper.writerWithDefaultPrettyPrinter()
                    .without(com.fasterxml.jackson.core.JsonGenerator.Feature.AUTO_CLOSE_TARGET)
                    .writeValue(zos, backupData);
                zos.closeEntry();

                // Entry 2: Media subfolder
                Path tenantMediaPath = tenantMediaPath(tenantId);
                if (Files.exists(tenantMediaPath) && Files.isDirectory(tenantMediaPath)) {
                    try (var files = Files.walk(tenantMediaPath)) {
                        for (Path file : files.toList()) {
                            if (Files.isSymbolicLink(file)) throw new IOException("Backup media contains a symbolic link");
                            if (!Files.isRegularFile(file)) continue;
                            String relativePath = "media/" + tenantMediaPath.relativize(file).toString().replace("\\", "/");
                            zos.putNextEntry(new ZipEntry(relativePath));
                            Files.copy(file, zos);
                            zos.closeEntry();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to export backup for tenant: " + tenantId, e);
            throw new RuntimeException("Export backup failed", e);
        }
    }

    @Override
    public List<DemoTemplateDescriptor> getAvailableTemplates(java.util.Locale locale) {
        return dynamicDemoTemplateRegistry.discoverTemplates(locale);
    }

    @Override
    public void importDemoTemplate(String tenantId, String templateId) {
        String effectiveId = (templateId == null || templateId.isBlank()) ? "kebab" : templateId.trim();
        java.io.InputStream is = dynamicDemoTemplateRegistry.openTemplateStream(effectiveId)
                .orElseThrow(() -> new IllegalArgumentException("Requested demo template '" + effectiveId + "' not found."));
        try (is) {
            importDemoTemplate(tenantId, is);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read template: " + effectiveId, e);
        }
    }

    /** Which archives must come from the version running here. */
    private enum VersionCheck {
        /** Production bundles: promoting needs the sandbox and production on the same version. */
        BUNDLES,
        /** Demo templates are part of the image, whatever version made them. */
        NEVER
    }

    /** A backup of any version is restored; a production bundle restored this way is still checked. */
    @Override
    public void importRestore(String tenantId, InputStream inputStream) {
        restoreArchive(tenantId, inputStream, false, VersionCheck.BUNDLES);
    }

    /**
     * Promotes a sandbox location to production. The bundle must come from the version running
     * here: the sandbox and production always run the same one, and data from another version
     * could break the promoted location.
     */
    @Override
    public void importProductionBundle(String tenantId, InputStream inputStream) {
        restoreArchive(tenantId, inputStream, true, VersionCheck.BUNDLES, true);
    }

    @Override
    public void importDemoTemplate(String tenantId, InputStream inputStream) {
        restoreArchive(tenantId, inputStream, true, VersionCheck.NEVER);
    }

    private void restoreArchive(String tenantId, InputStream inputStream, boolean templateOnly, VersionCheck check) {
        restoreArchive(tenantId, inputStream, templateOnly, check, false);
    }

    /** @param bundleExpected the archive is imported as a production bundle, even if it is not marked as one */
    private void restoreArchive(String tenantId, InputStream inputStream, boolean templateOnly, VersionCheck check,
            boolean bundleExpected) {
        Path destination = tenantMediaPath(tenantId);
        try (BackupMediaReplacement media = new BackupMediaReplacement(destination)) {
            TenantBackupData backupData = readArchive(inputStream, media.stagedMedia());
            boolean bundle = bundleExpected || backupData.isProductionBundle();
            if (check == VersionCheck.BUNDLES && bundle && !version.equals(backupData.getAppVersion())) {
                throw new TenantBackupRestorePort.VersionMismatch(backupData.getAppVersion(), version);
            }
            if (templateOnly) {
                // A public template must never introduce sample credentials or old customer orders.
                backupData.setUsers(java.util.List.of());
                backupData.setOrders(java.util.List.of());
                backupData.setOrderCounter(null);
            }
            inTenantTransaction(tenantId, false, () -> {
                cleanDatabase(tenantId);
                restoreDatabase(tenantId, backupData);
                entityManager.flush();
                media.install();
            });
            media.committed();
            String theme = backupData.getLocationTheme();
            // Backups and bundles bring their theme; a demo template keeps the one chosen with it
            if (check != VersionCheck.NEVER && theme != null && THEME_ID.matcher(theme).matches()) {
                appearance.saveLocationTheme(tenantId, theme);
            }
            log.info("Successfully restored backup data for tenant: {}", tenantId);
        } catch (TenantBackupRestorePort.VersionMismatch e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Restore backup failed", e);
        }
    }

    @Override
    public es.brasatech.fastbite.application.tenant.TenantBackupRestorePort.BackupPreview previewBackup(String tenantId, InputStream inputStream) {
        try (BackupMediaReplacement media = new BackupMediaReplacement(tenantMediaPath(tenantId))) {
            var data = readArchive(inputStream, media.stagedMedia());
            return new es.brasatech.fastbite.application.tenant.TenantBackupRestorePort.BackupPreview(
                    data.getProducts()==null?0:data.getProducts().size(), data.getOrders()==null?0:data.getOrders().size(), data.getUsers()==null?0:data.getUsers().size());
        } catch (Exception e) { throw new IllegalArgumentException("This file is not a supported FastBite backup. Choose an exported ZIP and try again."); }
    }

    private void restoreDatabase(String tenantId, TenantBackupData backupData) {
        // Rewrite product image URLs to match the new tenant ID
        if (backupData.getProducts() != null) {
            for (var prod : backupData.getProducts()) {
                if (prod.getImage() != null) {
                    prod.setImage(rewriteImageUrl(prod.getImage(), tenantId));
                }
            }
        }

        // Resolve parent references for child/translation entities to prevent TransientPropertyValueException
        org.hibernate.Session session = entityManager.unwrap(org.hibernate.Session.class);

        if (backupData.getCustomizationOptions() != null) {
            for (var opt : backupData.getCustomizationOptions()) {
                if (opt.getCustomization() != null && opt.getCustomization().getId() != null) {
                    opt.setCustomization(session.getReference(es.brasatech.fastbite.jpa.customization.CustomizationEntity.class, opt.getCustomization().getId()));
                }
            }
        }

        // Backups made before translations moved into each row carry them as separate rows
        applyLegacyTranslations(backupData.getGroups(), backupData.getGroupTranslations(), "group",
                GroupEntity::getId, GroupEntity::setTranslations);
        applyLegacyTranslations(backupData.getProducts(), backupData.getProductTranslations(), "product",
                ProductEntity::getId, ProductEntity::setTranslations);
        applyLegacyTranslations(backupData.getCustomizations(), backupData.getCustomizationTranslations(), "customization",
                CustomizationEntity::getId, CustomizationEntity::setTranslations);
        List<CustomizationOptionEntity> options = new ArrayList<>(backupData.getCustomizationOptions());
        backupData.getCustomizations().forEach(c -> options.addAll(c.getOptions()));
        applyLegacyTranslations(options, backupData.getCustomizationOptionTranslations(), "customizationOption",
                CustomizationOptionEntity::getId, CustomizationOptionEntity::setTranslations);
        applyLegacyTranslations(backupData.getDiscountRules(), backupData.getDiscountRuleTranslations(), "discountRule",
                DiscountRuleEntity::getId, DiscountRuleEntity::setTranslations);
        applyLegacyTranslations(backupData.getTables(), backupData.getTableTranslations(), "table",
                TableEntity::getId, TableEntity::setTranslations);

        // Populate database (ordered to satisfy dependencies using Hibernate replicate)
        replicateAll(backupData.getGroups());
        replicateAll(backupData.getProducts());
        replicateAll(backupData.getCustomizations());
        replicateAll(backupData.getTables());
        replicateAll(backupData.getDiscountRules());
        replicateAll(backupData.getOrders());
        if (backupData.getUsers() != null) {
            for (UserEntity user : backupData.getUsers()) {
                if (isPlatformSchema(tenantId)) {
                    // Older demo archives can contain platform owners: never restore those through a restaurant.
                    if (!isDemoStaff(user)) continue;
                    if (userJpaRepository.existsById(user.getId()) || userJpaRepository.findByUsername(user.getUsername()).isPresent()) {
                        throw new IllegalArgumentException("Backup staff account conflicts with a protected platform account");
                    }
                }
                // Restored accounts belong to the destination restaurant, not the source archive.
                user.setTenantId(tenantId);
                replicateAll(List.of(user));
            }
        }
        replicateAll(backupData.getPaymentConfigs());
        if (backupData.getFormatVersion() >= 2) entityManager.createNativeQuery("DELETE FROM restaurant_settings").executeUpdate();
        if (backupData.getRestaurantSettings() != null) entityManager.merge(backupData.getRestaurantSettings());
        // Archives from before fiscal profiles leave the destination's profile as it is
        if (backupData.getFormatVersion() >= 3) entityManager.createNativeQuery("DELETE FROM fiscal_profile").executeUpdate();
        if (backupData.getFiscalProfile() != null) entityManager.merge(backupData.getFiscalProfile());
        entityManager.flush();
        restoreOrderCounter(backupData.getOrderCounter());

    }

    @Override
    public void cleanTenantData(String tenantId) {
        Path destination = tenantMediaPath(tenantId);
        try (BackupMediaReplacement media = new BackupMediaReplacement(destination)) {
            inTenantTransaction(tenantId, false, () -> {
                cleanDatabase(tenantId);
                media.install();
            });
            media.committed();
        } catch (IOException e) {
            throw new IllegalStateException("Clean tenant media failed", e);
        }
    }

    private void cleanDatabase(String tenantId) {
        try {

            // 1. Delete all database records (reverse dependency order using native queries to handle element collections)
            entityManager.createNativeQuery("DELETE FROM table_orders").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM dining_tables").executeUpdate();
            
            entityManager.createNativeQuery("DELETE FROM cart_item_customizations").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM cart_items").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM orders").executeUpdate();
            
            entityManager.createNativeQuery("DELETE FROM group_products").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM groups").executeUpdate();
            
            entityManager.createNativeQuery("DELETE FROM product_customizations").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM products").executeUpdate();
            
            entityManager.createNativeQuery("DELETE FROM customization_options").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM customizations").executeUpdate();
            
            entityManager.createNativeQuery("DELETE FROM discount_rules").executeUpdate();
            
            if (isPlatformSchema(tenantId)) {
                String staff = "SELECT u.id FROM users u WHERE LOWER(u.tenant_id) IN ('kebab', 'default') "
                        + "AND NOT EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id = u.id AND r.role = 'OWNER')";
                entityManager.createNativeQuery("DELETE FROM user_roles WHERE user_id IN (" + staff + ")").executeUpdate();
                entityManager.createNativeQuery("DELETE FROM users WHERE id IN (" + staff + ")").executeUpdate();
            } else {
                entityManager.createNativeQuery("DELETE FROM user_roles").executeUpdate();
                entityManager.createNativeQuery("DELETE FROM users").executeUpdate();
            }
            
            entityManager.createNativeQuery("DELETE FROM payment_modes").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM money_denominations").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM payment_configs").executeUpdate();

            entityManager.flush();
            entityManager.clear();


        } catch (Exception e) {
            log.error("Failed to clean data for tenant: " + tenantId, e);
            throw new RuntimeException("Clean tenant data failed", e);
        }
    }

    private void inTenantTransaction(String tenantId, boolean readOnly, Runnable work) {
        String originalTenant = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(tenantId);
        try {
            // Tenant must be selected before Hibernate opens its session. Suspend any caller's session.
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setReadOnly(readOnly);
            transaction.executeWithoutResult(status -> work.run());
        } finally {
            if (originalTenant == null) TenantContext.clear();
            else TenantContext.setCurrentTenant(originalTenant);
        }
    }

    private static boolean isPlatformSchema(String tenantId) {
        return "kebab".equalsIgnoreCase(tenantId) || "default".equalsIgnoreCase(tenantId);
    }

    private static boolean isDemoStaff(UserEntity user) {
        return isPlatformSchema(user.getTenantId())
                && (user.getRoles() == null || !user.getRoles().contains(es.brasatech.fastbite.domain.user.Role.OWNER));
    }

    private Path tenantMediaPath(String tenantId) {
        if (tenantId == null || !tenantId.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid backup tenant identifier");
        }
        Path root = Paths.get(uploadDirectory).toAbsolutePath().normalize();
        Path path = root.resolve(tenantId).normalize();
        for (Path parent = path; parent != null; parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) throw new IllegalArgumentException("Media path contains a symbolic link");
        }
        if (!path.startsWith(root) || path.equals(root)) throw new IllegalArgumentException("Invalid media path");
        return path;
    }

    private TenantBackupData readArchive(InputStream input, Path stagedMedia) throws IOException {
        TenantBackupData data = null;
        long remaining = 256L * 1024 * 1024;
        java.util.Set<Path> files = new java.util.HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName().replace("\\", "/");
                ByteArrayOutputStream json = null;
                OutputStream target;
                if ("data.json".equalsIgnoreCase(name) || name.endsWith("/data.json")) {
                    if (data != null) throw new IOException("Duplicate data.json");
                    json = new ByteArrayOutputStream();
                    target = json;
                } else if (name.startsWith("media/") && !entry.isDirectory()) {
                    Path file = stagedMedia.resolve(name.substring(6)).normalize();
                    if (!file.startsWith(stagedMedia) || file.equals(stagedMedia) || !files.add(file)) {
                        throw new IOException("Invalid or duplicate backup media path");
                    }
                    Files.createDirectories(file.getParent());
                    target = Files.newOutputStream(file);
                } else {
                    target = OutputStream.nullOutputStream();
                }
                try (OutputStream out = target) {
                    byte[] buffer = new byte[8192];
                    for (int read; (read = zip.read(buffer)) != -1;) {
                        remaining -= read;
                        if (remaining < 0) throw new IOException("Backup exceeds 256 MiB expanded limit");
                        out.write(buffer, 0, read);
                    }
                }
                if (json != null) {
                    data = objectMapper.readValue(json.toByteArray(), TenantBackupData.class);
                    if (data == null || data.getFormatVersion() < 1 || data.getFormatVersion() > 3) {
                        throw new IOException("Invalid or unsupported backup format");
                    }
                }
                zip.closeEntry();
            }
        }
        if (data == null) throw new IOException("Invalid backup file: data.json not found");
        return data;
    }

    private void replicateAll(List<?> entities) {
        if (entities == null) return;
        org.hibernate.Session session = entityManager.unwrap(org.hibernate.Session.class);
        for (Object entity : entities) {
            session.replicate(entity, org.hibernate.ReplicationMode.OVERWRITE);
        }
    }

    private void restoreOrderCounter(OrderCounterBackup saved) {
        java.time.LocalDate today = java.time.LocalDate.now(ordersTimeZone);
        int highestOrder = ((Number) entityManager.createNativeQuery("SELECT COALESCE(MAX(order_number), 0) FROM orders WHERE created_at >= :start AND created_at < :end")
                .setParameter("start", today.atStartOfDay()).setParameter("end", today.plusDays(1).atStartOfDay()).getSingleResult()).intValue();
        int restoredCounter = saved != null && today.equals(saved.businessDay()) ? saved.lastNumber() : 0;
        // Keep numbers already issued today in the destination; older backups must never rewind them.
        entityManager.createNativeQuery("UPDATE order_counter SET last_number = GREATEST(CASE WHEN business_day = :today THEN last_number ELSE 0 END, :restored), business_day = :today WHERE id = 1")
                .setParameter("today", today).setParameter("restored", Math.max(highestOrder, restoredCounter)).executeUpdate();
    }

    public record OrderCounterBackup(java.time.LocalDate businessDay, int lastNumber) {}

    // Inner DTO helper for serialization
    @lombok.Data
    public static class TenantBackupData {
        // Older demo/translation archives have no explicit version.
        private int formatVersion = 1;
        /** The FastBite version that exported it; absent in archives made before it was recorded. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String appVersion;
        /** A production bundle, for promoting a sandbox location, rather than a backup. */
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private boolean productionBundle;
        private List<GroupEntity> groups = new ArrayList<>();
        private List<ProductEntity> products = new ArrayList<>();
        private List<CustomizationEntity> customizations = new ArrayList<>();
        private List<CustomizationOptionEntity> customizationOptions = new ArrayList<>();
        private List<TableEntity> tables = new ArrayList<>();
        private List<DiscountRuleEntity> discountRules = new ArrayList<>();
        private List<OrderEntity> orders = new ArrayList<>();
        private List<UserEntity> users = new ArrayList<>();
        private List<PaymentConfigEntity> paymentConfigs = new ArrayList<>();
        private es.brasatech.fastbite.jpa.settings.RestaurantSettingsEntity restaurantSettings;
        /** The location's own theme, when it has one; archives made before it was recorded have none. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String locationTheme;
        private FiscalProfileEntity fiscalProfile;
        private OrderCounterBackup orderCounter;

        // Legacy: translations as separate rows, only present in backups made before translations
        // moved into each entity. Read on restore, never written.
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<Map<String, Object>> groupTranslations = new ArrayList<>();
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<Map<String, Object>> productTranslations = new ArrayList<>();
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<Map<String, Object>> customizationTranslations = new ArrayList<>();
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<Map<String, Object>> customizationOptionTranslations = new ArrayList<>();
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<Map<String, Object>> discountRuleTranslations = new ArrayList<>();
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<Map<String, Object>> tableTranslations = new ArrayList<>();
    }

    /** Merges legacy translation rows ({parentKey: {id}, language, name, description}) into their parent entities. */
    private static <E> void applyLegacyTranslations(List<E> entities, List<Map<String, Object>> rows, String parentKey,
                                                    Function<E, String> id,
                                                    BiConsumer<E, Map<String, Map<String, String>>> setTranslations) {
        if (entities == null || rows == null || rows.isEmpty()) {
            return;
        }
        Map<String, Map<String, Map<String, String>>> byParent = new HashMap<>();
        for (Map<String, Object> row : rows) {
            if (row.get(parentKey) instanceof Map<?, ?> parent && row.get("language") instanceof String language) {
                Map<String, String> fields = byParent.computeIfAbsent(String.valueOf(parent.get("id")), k -> new HashMap<>())
                        .computeIfAbsent(language, k -> new HashMap<>());
                for (String field : List.of("name", "description")) {
                    if (row.get(field) instanceof String value && !value.isBlank()) {
                        fields.put(field, value);
                    }
                }
            }
        }
        for (E entity : entities) {
            Map<String, Map<String, String>> translations = byParent.get(id.apply(entity));
            if (translations != null) {
                setTranslations.accept(entity, translations);
            }
        }
    }

    private String rewriteImageUrl(String originalUrl, String newTenantId) {
        if (originalUrl == null) return null;
        if (originalUrl.startsWith("/user-images/")) {
            String remainder = originalUrl.substring("/user-images/".length());
            int nextSlash = remainder.indexOf('/');
            if (nextSlash != -1) {
                return "/user-images/" + newTenantId + remainder.substring(nextSlash);
            }
        }
        if (originalUrl.startsWith("media/")) {
            return "/user-images/" + newTenantId + "/" + originalUrl.substring("media/".length());
        }
        return originalUrl;
    }
}
