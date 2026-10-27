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
}
