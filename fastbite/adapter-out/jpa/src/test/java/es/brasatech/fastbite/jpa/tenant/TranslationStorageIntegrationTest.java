package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.office.I18nConfig;
import es.brasatech.fastbite.domain.I18nField;
import es.brasatech.fastbite.domain.TranslatableText;
import es.brasatech.fastbite.domain.TranslatableType;
import es.brasatech.fastbite.domain.customization.CustomizationDto;
import es.brasatech.fastbite.domain.customization.CustomizationOptionDto;
import es.brasatech.fastbite.domain.product.ProductDto;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.customization.CustomizationJpaRepository;
import es.brasatech.fastbite.jpa.customization.CustomizationServiceJpaImpl;
import es.brasatech.fastbite.jpa.discount.DiscountRuleJpaRepository;
import es.brasatech.fastbite.jpa.group.GroupJpaRepository;
import es.brasatech.fastbite.jpa.product.ProductJpaRepository;
import es.brasatech.fastbite.jpa.i18n.TranslationServiceJpaImpl;
import es.brasatech.fastbite.jpa.product.ProductServiceJpaImpl;
import es.brasatech.fastbite.jpa.table.TableJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = es.brasatech.fastbite.jpa.TestConfig.class)
class TranslationStorageIntegrationTest {

    @Autowired
    private TenantProvisionerAdapter provisioner;
    @Autowired
    private TenantBackupRestoreAdapter backupRestoreAdapter;
    @Autowired
    private ProductJpaRepository productRepository;
    @Autowired
    private CustomizationJpaRepository customizationRepository;
    @Autowired
    private GroupJpaRepository groupRepository;
    @Autowired
    private TableJpaRepository tableRepository;
    @Autowired
    private DiscountRuleJpaRepository discountRepository;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private DataSource dataSource;

    private final I18nConfig i18nConfig = new I18nConfig(); // default language "en"

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        LocaleContextHolder.resetLocaleContext();
    }

    private TranslationServiceJpaImpl translations() {
        return new TranslationServiceJpaImpl(groupRepository, productRepository, customizationRepository,
                tableRepository, discountRepository, i18nConfig);
    }

    /** Reads as a guest browsing in the given language would. */
    private <T> T inLanguage(String language, String tenant, Supplier<T> work) {
        LocaleContextHolder.setLocale(Locale.of(language));
        return inTenant(tenant, work);
    }

    private <T> T inTenant(String tenant, Supplier<T> work) {
        TenantContext.setCurrentTenant(tenant);
        return tx.execute(status -> work.get());
    }

    private static I18nField i18n(String en, String es) {
        return new I18nField(Map.of("en", en, "es", es));
    }

    @Test
    void productTranslationsAreReadInTheRequestedLanguageAndSurvivePlainEdits() {
        provisioner.provisionTenant("i18nproducts");
        var service = new ProductServiceJpaImpl(productRepository, i18nConfig);

        String id = inTenant("i18nproducts", () -> service.create(
                new ProductDto(null, "Kebab", BigDecimal.TEN, "Beef kebab", "/k.webp", Set.of(), true)).id());
        inTenant("i18nproducts", () -> translations().saveTexts(TranslatableType.PRODUCT, id, Map.of(
                "name", i18n("Kebab", "Kebab de ternera"),
                "description", i18n("Beef kebab", "Kebab de ternera con salsa"))));

        assertThat(inLanguage("es", "i18nproducts", () -> service.findById(id)).orElseThrow().name())
                .isEqualTo("Kebab de ternera");
        assertThat(inLanguage("pt", "i18nproducts", () -> service.findById(id)).orElseThrow().name())
                .as("missing language falls back to the default").isEqualTo("Kebab");

        // A plain back-office edit changes the default-language texts only
        inTenant("i18nproducts", () -> service.update(id,
                new ProductDto(id, "Kebab XL", BigDecimal.valueOf(12), "Big beef kebab", "/k.webp", Set.of(), true)));

        List<TranslatableText> stored = inTenant("i18nproducts",
                () -> translations().findTexts(TranslatableType.PRODUCT, id)).orElseThrow();
        assertThat(stored).extracting(TranslatableText::key).containsExactly("name", "description");
        assertThat(stored.get(0).value().getAll()).isEqualTo(Map.of("en", "Kebab XL", "es", "Kebab de ternera"));
        assertThat(stored.get(1).value().get("es", "en")).isEqualTo("Kebab de ternera con salsa");
    }

    @Test
    void optionTranslationsSurviveAPlainEditOfTheCustomization() {
        provisioner.provisionTenant("i18noptions");
        var service = new CustomizationServiceJpaImpl(customizationRepository, i18nConfig);
        List<CustomizationOptionDto> options = List.of(
                new CustomizationOptionDto(null, "Garlic", BigDecimal.ZERO, true, 0),
                new CustomizationOptionDto(null, "Spicy", BigDecimal.ZERO, false, 0));

        String id = inTenant("i18noptions", () -> service.create(new CustomizationDto(null, "Sauce", "radio", options, 0)).id());
        List<TranslatableText> texts = inTenant("i18noptions",
                () -> translations().findTexts(TranslatableType.CUSTOMIZATION, id)).orElseThrow();
        assertThat(texts).extracting(TranslatableText::key)
                .containsExactly("name", "option_" + id + "-opt-0", "option_" + id + "-opt-1");
        inTenant("i18noptions", () -> translations().saveTexts(TranslatableType.CUSTOMIZATION, id, Map.of(
                "name", i18n("Sauce", "Salsa"),
                "option_" + id + "-opt-0", i18n("Garlic", "Ajo"),
                "option_" + id + "-opt-1", i18n("Spicy", "Picante"))));

        // Plain edit: options are recreated, e.g. with a new price
        inTenant("i18noptions", () -> service.update(id, new CustomizationDto(id, "Sauce", "radio", List.of(
                new CustomizationOptionDto(id + "-opt-0", "Garlic", BigDecimal.ONE, true, 0),
                new CustomizationOptionDto(id + "-opt-1", "Spicy", BigDecimal.ONE, false, 0)), 0)));

        CustomizationDto spanish = inLanguage("es", "i18noptions", () -> service.findById(id)).orElseThrow();
        assertThat(spanish.name()).isEqualTo("Salsa");
        assertThat(spanish.options()).extracting(CustomizationOptionDto::name).containsExactly("Ajo", "Picante");
        assertThat(spanish.options()).allSatisfy(option -> assertThat(option.price()).isEqualByComparingTo(BigDecimal.ONE));
    }

    @Test
    void savingSomeTextsKeepsTheOthersAndUnknownItemsAreReported() {
        provisioner.provisionTenant("i18npartial");
        var service = new ProductServiceJpaImpl(productRepository, i18nConfig);
        String id = inTenant("i18npartial", () -> service.create(
                new ProductDto(null, "Kebab", BigDecimal.TEN, "Beef kebab", "/k.webp", Set.of(), true)).id());

        inTenant("i18npartial", () -> translations().saveTexts(TranslatableType.PRODUCT, id,
                Map.of("name", i18n("Kebab", "Kebab ES"))));

        assertThat(inLanguage("es", "i18npartial", () -> service.findById(id)).orElseThrow().description())
                .isEqualTo("Beef kebab");
        assertThat(inTenant("i18npartial", () -> translations().findTexts(TranslatableType.GROUP, "missing"))).isEmpty();
        assertThat(inTenant("i18npartial", () -> translations().saveTexts(TranslatableType.TABLE, "missing", Map.of())))
                .isFalse();
    }

    @Test
    void legacyTranslationTablesAreMovedIntoTheRowsOnce() throws Exception {
        provisioner.provisionTenant("i18nlegacy");
        String schema = "tenant_i18nlegacy";
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO " + schema + ".groups (id, name, description) VALUES ('g1', 'Burgers', 'Beef')");
            sql.execute("CREATE TABLE " + schema + ".group_translations (id VARCHAR(36) PRIMARY KEY, group_id VARCHAR(36), "
                    + "language VARCHAR(10), name VARCHAR(255), description VARCHAR(1000))");
            sql.execute("INSERT INTO " + schema + ".group_translations VALUES ('t1', 'g1', 'es', 'Hamburguesas', 'Ternera'), "
                    + "('t2', 'g1', 'pt', 'Hambúrgueres', NULL)");

            LegacyTranslationMigration.migrate(connection, schema);
            LegacyTranslationMigration.migrate(connection, schema); // later startups find nothing to do

            try (ResultSet tables = sql.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE LOWER(table_schema) = '" + schema + "' AND LOWER(table_name) = 'group_translations'")) {
                tables.next();
                assertThat(tables.getInt(1)).as("legacy table dropped").isZero();
            }
        }

        var group = inTenant("i18nlegacy", () -> groupRepository.findById("g1")).orElseThrow();
        assertThat(group.getTranslations()).isEqualTo(Map.of(
                "es", Map.of("name", "Hamburguesas", "description", "Ternera"),
                "pt", Map.of("name", "Hambúrgueres")));
    }

    @Test
    void backupsInTheOldFormatRestoreTheirTranslations() throws Exception {
        provisioner.provisionTenant("i18nrestore");
        String legacyBackup = """
                {"groups": [{"id": "g1", "name": "Burgers", "description": "Beef", "icon": "fa-burger", "products": []}],
                 "groupTranslations": [{"id": "t1", "group": {"id": "g1", "name": "Burgers"},
                                        "language": "es", "name": "Hamburguesas", "description": "Ternera"}]}
                """;
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(zip)) {
            out.putNextEntry(new ZipEntry("data.json"));
            out.write(legacyBackup.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        // As in the app, the request's tenant is already set when the restore runs
        TenantContext.setCurrentTenant("i18nrestore");
        backupRestoreAdapter.importRestore("i18nrestore", new ByteArrayInputStream(zip.toByteArray()));

        var group = inTenant("i18nrestore", () -> groupRepository.findById("g1")).orElseThrow();
        assertThat(group.getTranslations().get("es")).isEqualTo(Map.of("name", "Hamburguesas", "description", "Ternera"));

        // And a new export carries them inside the group, without the legacy lists
        ByteArrayOutputStream export = new ByteArrayOutputStream();
        TenantContext.setCurrentTenant("i18nrestore");
        backupRestoreAdapter.exportBackup("i18nrestore", export);
        String data = new String(firstEntry(export.toByteArray()), StandardCharsets.UTF_8);
        assertThat(data).contains("\"translations\"").contains("Hamburguesas").doesNotContain("groupTranslations");
    }

    private static byte[] firstEntry(byte[] zipBytes) throws Exception {
        try (var in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            in.getNextEntry();
            return in.readAllBytes();
        }
    }
}
