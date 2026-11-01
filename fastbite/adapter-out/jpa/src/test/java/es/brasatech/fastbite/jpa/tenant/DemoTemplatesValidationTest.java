package es.brasatech.fastbite.jpa.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.product.ProductJpaRepository;
import es.brasatech.fastbite.jpa.table.TableJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = es.brasatech.fastbite.jpa.TestConfig.class)
class DemoTemplatesValidationTest {

    @Autowired
    private TenantBackupRestoreAdapter backupRestoreAdapter;

    @Autowired
    private TenantProvisionerAdapter tenantProvisionerAdapter;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired private ProductJpaRepository products;
    @Autowired private TableJpaRepository tables;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private File locateTemplateZip(String templateName) {
        Path current = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && current != null; i++) {
            Path candidate = current.resolve("demo-templates").resolve(templateName + ".zip");
            if (java.nio.file.Files.exists(candidate) && java.nio.file.Files.isRegularFile(candidate)) {
                return candidate.toFile();
            }
            candidate = current.resolve(templateName + ".zip");
            if (java.nio.file.Files.exists(candidate) && java.nio.file.Files.isRegularFile(candidate)) {
                return candidate.toFile();
            }
            current = current.getParent();
        }

        // Check classpath resource
        var res = getClass().getResource("/" + templateName + ".zip");
        if (res != null) {
            try {
                return Paths.get(res.toURI()).toFile();
            } catch (Exception ignored) {
            }
        }

        fail("Could not find demo template archive for: " + templateName);
        return null;
    }

    @ParameterizedTest
    @ValueSource(strings = {"cafe_demo", "michelin_demo", "kebab_demo"})
    void validateArchiveIntegrityAndAssets(String templateName) throws IOException {
        File zipFile = locateTemplateZip(templateName);
        assertNotNull(zipFile);
        assertTrue(zipFile.length() > 0, "Zip file must not be empty");

        try (ZipFile zip = new ZipFile(zipFile)) {
            ZipEntry dataEntry = zip.getEntry("data.json");
            assertNotNull(dataEntry, "Archive " + templateName + " must contain data.json");

            ZipEntry templateEntry = zip.getEntry("template.json");
            if (templateEntry != null) {
                try (InputStream is = zip.getInputStream(templateEntry)) {
                    JsonNode manifest = objectMapper.readTree(is);
                    assertTrue(manifest.has("id"), "template.json must have an id field");
                    assertTrue(manifest.has("name"), "template.json must have a name field");
                }
            }

            Set<String> mediaEntries = new HashSet<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("media/") && !entry.isDirectory()) {
                    mediaEntries.add(name);
                }
            }

            try (InputStream is = zip.getInputStream(dataEntry)) {
                JsonNode dataJson = objectMapper.readTree(is);
                int version = dataJson.has("formatVersion") ? dataJson.get("formatVersion").asInt() : 1;
                assertTrue(version >= 1 && version <= 3, "Format version should be between 1 and 3");

                if (dataJson.has("products") && dataJson.get("products").isArray()) {
                    for (JsonNode product : dataJson.get("products")) {
                        assertTrue(product.has("name"), "Product must have a name");
                        assertTrue(product.has("allergens") && product.get("allergens").isArray(),
                                "Demo dish " + product.path("id").asText() + " must declare its allergens");
                        for (JsonNode allergen : product.get("allergens")) {
                            es.brasatech.fastbite.domain.allergen.Allergen.valueOf(allergen.asText());
                        }
                        if (product.hasNonNull("image")) {
                            String image = product.get("image").asText().trim();
                            if (!image.isEmpty()) {
                                if (image.startsWith("http://") || image.startsWith("https://")) {
                                    // Valid remote URL
                                    continue;
                                }

                                if (image.startsWith("/user-images/")) {
                                    String relative = image.replaceFirst("^/user-images/[^/]+/", "");
                                    boolean foundInMedia = mediaEntries.contains("media/" + relative)
                                            || mediaEntries.contains(relative);
                                    assertTrue(foundInMedia,
                                            "Product image '" + image + "' in " + templateName +
                                                    " must have corresponding media asset in zip (checked: media/" + relative + ")");
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cafe_demo", "michelin_demo", "kebab_demo"})
    void packagedCopiesHaveSpanishTablesAndCompleteTranslatedMenu(String templateName) throws IOException {
        Path external = locateTemplateZip(templateName).toPath();
        Path root = external.getParent().getParent();
        Path bundled = root.resolve("fastbite/adapter-in/web/src/main/resources").resolve(templateName + ".zip");
        assertArrayEquals(Files.readAllBytes(external), Files.readAllBytes(bundled), "deployment archives must match");
        try (ZipFile zip = new ZipFile(external.toFile())) {
            JsonNode data = objectMapper.readTree(zip.getInputStream(zip.getEntry("data.json")));
            for (String collection : new String[]{"groups", "products"}) {
                for (JsonNode item : data.path(collection)) {
                    assertFalse(item.path("name").asText().contains("&"), item.path("id").asText());
                }
            }
            if ("michelin_demo".equals(templateName)) {
                JsonNode bisque = null;
                for (JsonNode product : data.path("products")) {
                    if ("prod-michelin-bogavante-azul".equals(product.path("id").asText())) bisque = product;
                }
                assertNotNull(bisque);
                assertTrue(bisque.path("description").asText().contains("apio y vino"));
                Set<String> allergens = new HashSet<>();
                bisque.path("allergens").forEach(a -> allergens.add(a.asText()));
                assertEquals(Set.of("CRUSTACEANS", "MILK", "CELERY", "SULPHITES"), allergens);
            }

            for (JsonNode table : data.path("tables")) {
                String id = table.path("id").asText();
                assertTrue(table.path("name").asText().startsWith("Mesa"), id);
                for (String language : new String[]{"en", "pt"}) {
                    assertTrue(hasTranslation(data.path("tableTranslations"), "table", id, language), id + " " + language);
                }
            }
            for (JsonNode product : data.path("products")) {
                String id = product.path("id").asText();
                assertTrue(product.path("allergens").isArray(), id);
                assertTrue(product.path("description").asText().startsWith("Ingredientes:"), id);
                for (String language : new String[]{"en", "pt"}) {
                    assertTrue(hasTranslation(data.path("productTranslations"), "product", id, language), id + " " + language);
                }
                String image = product.path("image").asText();
                assertTrue(image.startsWith("/user-images/kebab/"), id + " must have a local photo");
                String entryName = "media/" + image.substring("/user-images/kebab/".length());
                ZipEntry photo = zip.getEntry(entryName);
                assertNotNull(photo, id + " photo must be included");
                byte[] bytes = zip.getInputStream(photo).readAllBytes();
                // These bundled photos use the standard lossy VP8 WebP frame header.
                assertTrue(bytes.length >= 30, id + " photo is truncated");
                assertEquals("RIFF", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
                assertEquals("WEBPVP8 ", new String(bytes, 8, 8, StandardCharsets.US_ASCII));
                int width = ((bytes[26] & 255) | ((bytes[27] & 255) << 8)) & 0x3fff;
                int height = ((bytes[28] & 255) | ((bytes[29] & 255) << 8)) & 0x3fff;
                assertTrue(width > 0 && width <= 600 && height > 0 && height <= 800, id + " " + width + "x" + height);
            }
        }
    }

    private boolean hasTranslation(JsonNode translations, String parent, String id, String language) {
        for (JsonNode item : translations) {
            if (id.equals(item.path(parent).path("id").asText()) && language.equals(item.path("language").asText())
                    && !item.path("name").asText().isBlank()) return true;
        }
        return false;
    }

    @Test
    void validateEndToEndDemoTemplateImports() {
        String[] templates = {"cafe", "michelin", "kebab"};
        for (String templateId : templates) {
            String testTenant = "testimport" + templateId;
            tenantProvisionerAdapter.provisionTenant(testTenant);
            TenantContext.setCurrentTenant(testTenant);

            assertDoesNotThrow(() -> backupRestoreAdapter.importDemoTemplate(testTenant, templateId),
                    "Importing demo template " + templateId + " should not throw exceptions");

            transactionTemplate.executeWithoutResult(status -> {
                var importedProducts = products.findAll();
                assertFalse(importedProducts.isEmpty(), templateId);
                for (var product : importedProducts) {
                    assertNotNull(product.getAllergens(), product.getName());
                    assertTrue(product.getTranslations().containsKey("en"), product.getName());
                    assertTrue(product.getTranslations().containsKey("pt"), product.getName());
                }
                var importedTables = tables.findAll();
                assertFalse(importedTables.isEmpty(), templateId);
                for (var table : importedTables) {
                    assertTrue(table.getName().startsWith("Mesa"), table.getName());
                    assertTrue(table.getTranslations().containsKey("en"), table.getName());
                    assertTrue(table.getTranslations().containsKey("pt"), table.getName());
                }
            });

            backupRestoreAdapter.cleanTenantData(testTenant);
        }
    }
}
