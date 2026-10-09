package es.brasatech.fastbite.jpa.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = es.brasatech.fastbite.jpa.TestConfig.class)
@ActiveProfiles("jpa")
class DemoTemplatesValidationTest {

    @Autowired
    private TenantBackupRestoreAdapter backupRestoreAdapter;

    @Autowired
    private TenantProvisionerAdapter tenantProvisionerAdapter;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

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

    @Test
    void validateEndToEndDemoTemplateImports() {
        String[] templates = {"cafe", "michelin", "kebab"};
        for (String templateId : templates) {
            String testTenant = "testimport" + templateId;
            tenantProvisionerAdapter.provisionTenant(testTenant);
            TenantContext.setCurrentTenant(testTenant);

            assertDoesNotThrow(() -> backupRestoreAdapter.importDemoTemplate(testTenant, templateId),
                    "Importing demo template " + templateId + " should not throw exceptions");

            backupRestoreAdapter.cleanTenantData(testTenant);
        }
    }
}
