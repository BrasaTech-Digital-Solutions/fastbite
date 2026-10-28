package es.brasatech.fastbite;

import es.brasatech.fastbite.application.tenant.SubscriptionGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production linked to a sandbox: restaurants are set up in the sandbox, never from demo concepts here. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:linked-production;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.jakarta.persistence.jdbc.url=jdbc:h2:mem:linked-production;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.mail.password=",
        "fastbite.migrations.mode=migrate", "spring.jpa.hibernate.ddl-auto=none", "spring.session.jdbc.initialize-schema=never",
        "stripe.secret.key=", "stripe.webhook.secret=", "stripe.platform.webhook.secret=", "stripe.restaurant-price-id=", "stripe.connected-account=",
        "fastbite.environment=production",
        // A sandbox nobody answers at: production must work regardless
        "fastbite.link.url=http://127.0.0.1:9", "fastbite.link.secret=a-secret-of-at-least-thirty-two-characters"})
class LinkedProductionFlowTest {

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SubscriptionGateway gateway;
    @MockitoBean es.brasatech.fastbite.application.platform.SandboxLinkPort sandboxLink;
    @Value("${fastbite.version:dev}") String version;

    @org.junit.jupiter.api.BeforeEach
    void setUpSandboxLink() {
        org.mockito.Mockito.doThrow(new es.brasatech.fastbite.application.platform.SandboxLinkPort.Unavailable(
                es.brasatech.fastbite.application.platform.SandboxLinkPort.Reason.UNREACHABLE, "Cannot connect"))
                .when(sandboxLink).provision(org.mockito.ArgumentMatchers.any());
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    private HttpResponse<String> get(HttpClient c, String path) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient c, String path, String body) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String csrf(String html) {
        var m = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertTrue(m.find());
        return URLEncoder.encode(m.group(1), StandardCharsets.UTF_8);
    }

    private HttpResponse<String> postJson(HttpClient c, String path, String json, String csrfToken) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (csrfToken != null) {
            builder.header("X-CSRF-TOKEN", csrfToken);
        }
        return c.send(builder.POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private byte[] createBundleWithProduct(String tenant, String prodId, String prodName, java.math.BigDecimal price) throws java.io.IOException {
        var baos = new java.io.ByteArrayOutputStream();
        try (var zos = new java.util.zip.ZipOutputStream(baos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("data.json"));
            String json = """
                    {
                      "formatVersion": 3,
                      "appVersion": "%s",
                      "productionBundle": true,
                      "groups": [
                        { "id": "g-1", "name": "Burgers" }
                      ],
                      "products": [
                        { "id": "%s", "name": "%s", "price": %s, "active": true }
                      ],
                      "tables": [
                        { "id": "table-1", "name": "Terrace 1", "seats": 4, "status": "AVAILABLE", "active": true }
                      ]
                    }
                    """.formatted(version, prodId, prodName, price);
            zos.write(json.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    @Test
    void theOwnerIsSentToTheSandboxToSetUpAndDemoConceptsStayThere() throws Exception {
        var owner = client();
        post(owner, "/signup", "acceptTerms=true&tenantId=linkedcafe&username=linkedowner&fullName=Linked+Owner&password=local-test-password&_csrf="
                + csrf(get(owner, "/signup").body()));
        post(owner, "/login", "username=linkedowner&password=local-test-password&_csrf=" + csrf(get(owner, "/login").body()));

        var overview = get(owner, "/owner/console");
        assertEquals(200, overview.statusCode());
        assertTrue(overview.body().contains("Set up linkedcafe in the sandbox"), overview.body());
        assertTrue(overview.body().contains("/owner/sandbox?location=linkedcafe"));
        assertFalse(overview.body().contains("What kind of restaurant are you running?"));
        String token = csrf(overview.body());

        var adopt = post(owner, "/owner/workspace/linkedcafe/setup", "action=adopt&template=kebab&theme=fastfood&_csrf=" + token);
        assertEquals(302, adopt.statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tenant_linkedcafe.products", Integer.class));
        assertEquals(409, post(owner, "/linkedcafe/api/backoffice/maintenance/restore-demo", "_csrf=" + token).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tenant_linkedcafe.products", Integer.class));

        var backup = get(owner, "/owner/console?view=location-settings&section=backup&location=linkedcafe").body();
        assertTrue(backup.contains("Bring in your sandbox setup"));
        assertFalse(backup.contains("Export Production Bundle"));
        assertFalse(get(owner, "/owner/console?view=location-settings&section=advanced&location=linkedcafe").body()
                .contains("Load the demo template"));

        // The sandbox cannot be reached: the owner is told it is being prepared, and nothing fails
        var enter = get(owner, "/owner/sandbox?location=linkedcafe");
        assertEquals(302, enter.statusCode());
        assertTrue(enter.headers().firstValue("location").orElse("").endsWith("/owner/console?location=linkedcafe"));
        assertTrue(get(owner, "/owner/console?location=linkedcafe").body().contains("is being prepared"));
    }


    @Test
    void promotionAndUndoPreserveProductionOrdersAndStaff() throws Exception {
        var owner = client();
        post(owner, "/signup", "acceptTerms=true&tenantId=promocafe&username=promoowner&fullName=Promo+Owner&password=local-test-password&_csrf="
                + csrf(get(owner, "/signup").body()));
        post(owner, "/login", "username=promoowner&password=local-test-password&_csrf=" + csrf(get(owner, "/login").body()));

        // Add a staff member, an order, and a trusted dining table in production
        jdbc.update("INSERT INTO tenant_promocafe.users (id, username, password, full_name, active) VALUES (?, ?, ?, ?, ?)",
                "staff-1", "promowaiter", "hashed", "Promo Waiter", true);
        jdbc.update("INSERT INTO tenant_promocafe.user_roles (user_id, role) VALUES (?, ?)", "staff-1", "WAITER");
        jdbc.update("INSERT INTO tenant_promocafe.orders (id, order_number, created_at, total) VALUES (?, ?, ?, ?)",
                "ord-promo-1", 101, java.sql.Timestamp.from(java.time.Instant.now()), new java.math.BigDecimal("25.50"));
        jdbc.update("INSERT INTO tenant_promocafe.dining_tables (id, name, seats, status, qr_trusted) VALUES (?, ?, ?, ?, ?)",
                "table-1", "Terrace 1", 4, "AVAILABLE", true);

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tenant_promocafe.users WHERE username='promowaiter'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tenant_promocafe.orders WHERE id='ord-promo-1'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tenant_promocafe.products", Integer.class));

        // Mock sandbox returning a bundle with a product
        byte[] bundleBytes = createBundleWithProduct("promocafe", "p-promo-1", "Sandbox Burger", new java.math.BigDecimal("9.50"));
        org.mockito.Mockito.when(sandboxLink.streamBundle(org.mockito.ArgumentMatchers.eq("promocafe"), org.mockito.ArgumentMatchers.eq("promoowner"), org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> new java.io.ByteArrayInputStream(bundleBytes));

        // 1. Preview promotion
        var backupPage = get(owner, "/owner/console?view=location-settings&section=backup&location=promocafe").body();
        String csrfToken = csrf(backupPage);

        var previewResp = postJson(owner, "/owner/workspace/promocafe/promote/preview", "{}", csrfToken);
        assertEquals(200, previewResp.statusCode(), previewResp.body());
        var previewJson = previewResp.body();
        assertTrue(previewJson.contains("\"token\":"), previewJson);
        var tokenMatcher = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(previewJson);
        assertTrue(tokenMatcher.find());
        String promoToken = tokenMatcher.group(1);

        // 2. Confirm promotion
        var confirmResp = postJson(owner, "/owner/workspace/promocafe/promote/confirm",
                "{\"token\":\"" + promoToken + "\"}", csrfToken);
        assertEquals(200, confirmResp.statusCode());
        assertTrue(confirmResp.body().contains("\"status\":\"applied\""), confirmResp.body());

        // Verify: product promoted, but staff, order, and table trust preserved!
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tenant_promocafe.products WHERE id='p-promo-1'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tenant_promocafe.users WHERE username='promowaiter'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tenant_promocafe.orders WHERE id='ord-promo-1'", Integer.class));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT qr_trusted FROM tenant_promocafe.dining_tables WHERE id='table-1'", Boolean.class));

        // 3. Undo promotion
        var undoResp = postJson(owner, "/owner/workspace/promocafe/promote/undo", "{}", csrfToken);
        assertEquals(200, undoResp.statusCode());
        assertTrue(undoResp.body().contains("\"status\":\"undone\""), undoResp.body());

        // Verify: product reverted to 0, staff, order, and table trust still preserved!
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tenant_promocafe.products", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tenant_promocafe.users WHERE username='promowaiter'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tenant_promocafe.orders WHERE id='ord-promo-1'", Integer.class));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT qr_trusted FROM tenant_promocafe.dining_tables WHERE id='table-1'", Boolean.class));
    }
}