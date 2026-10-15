package es.brasatech.fastbite;

import es.brasatech.fastbite.application.platform.LinkSignature;
import es.brasatech.fastbite.application.platform.SandboxSignInLink;
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
import java.time.Instant;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sandbox linked to production, entered as production does it: the location set up by a signed call,
 * the owner signed in with a single-use link. Setup happens here; locations and billing stay in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:linked-sandbox;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.jakarta.persistence.jdbc.url=jdbc:h2:mem:linked-sandbox;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.mail.password=",
        "fastbite.migrations.mode=migrate", "spring.jpa.hibernate.ddl-auto=none", "spring.session.jdbc.initialize-schema=never",
        "stripe.secret.key=", "stripe.webhook.secret=", "stripe.platform.webhook.secret=", "stripe.restaurant-price-id=", "stripe.connected-account=",
        "fastbite.environment=sandbox", "fastbite.version=9.9.9",
        "fastbite.link.url=https://fastbite.example", "fastbite.link.secret=" + LinkedSandboxFlowTest.SECRET})
class LinkedSandboxFlowTest {

    static final String SECRET = "a-secret-of-at-least-thirty-two-characters";
    static final String PRODUCTION = "https://fastbite.example";

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

    private static String location(HttpResponse<?> response) {
        return response.headers().firstValue("location").orElse("");
    }

    /** What production does: set the location up here, then hand the owner a link. */
    private HttpClient enteredFromProduction() throws Exception {
        byte[] body = "{\"tenantId\":\"practicecafe\",\"ownerUsername\":\"practiceowner\",\"ownerName\":\"Practice Owner\",\"plan\":\"RESTAURANT\"}"
                .getBytes(StandardCharsets.UTF_8);
        var setUp = client().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/link/locations"))
                .header("Content-Type", "application/json")
                .header("X-FastBite-Signature", LinkSignature.header(SECRET, Instant.now(), body))
                .header("X-FastBite-Version", "9.9.9")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, setUp.statusCode(), setUp.body());

        var owner = client();
        var entered = get(owner, "/link/enter?token=" + SandboxSignInLink.issue(SECRET, "practiceowner", "practicecafe", "9.9.9", Instant.now()));
        assertTrue(location(entered).endsWith("/owner/console?location=practicecafe"), location(entered));
        return owner;
    }

    @Test
    void theOwnerSetsUpHereAndTakesLocationsAndBillingToProduction() throws Exception {
        var owner = enteredFromProduction();

        var overview = get(owner, "/owner/console?location=practicecafe");
        assertEquals(200, overview.statusCode());
        assertTrue(overview.body().contains("What kind of restaurant are you running?"));
        assertTrue(overview.body().contains("Billing in FastBite"));
        String token = csrf(overview.body());

        post(owner, "/owner/workspace/practicecafe/setup", "action=adopt&template=kebab&theme=fastfood&_csrf=" + token);
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_practicecafe.products", Integer.class) > 0);
        var ready = get(owner, "/owner/console?location=practicecafe").body().replace("&amp;", "&");
        assertTrue(ready.contains("Take your setup to production"));
        assertTrue(ready.contains(PRODUCTION + "/owner/console?view=location-settings&section=backup&location=practicecafe"));
        assertTrue(ready.contains("Orders placed here are practice orders."));

        var locations = get(owner, "/owner/console?view=locations").body();
        assertTrue(locations.contains("Add a location in FastBite"));
        assertFalse(locations.contains("Create trial location"));
        assertTrue(get(owner, "/owner/console?view=settings").body().contains("managed in FastBite"));
        assertFalse(get(owner, "/owner/console?view=location-settings&location=practicecafe").body().contains("section=domain"));

        var added = post(owner, "/owner/add-location", "tenantId=sandboxonly&_csrf=" + token);
        assertEquals(PRODUCTION + "/owner/console", location(added));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_locations WHERE tenant_id='sandboxonly'", Integer.class));
        assertEquals(PRODUCTION + "/owner/billing/practicecafe", location(get(owner, "/owner/billing/practicecafe")));
        assertEquals(PRODUCTION + "/owner/console", location(post(owner, "/owner/billing/practicecafe/checkout", "_csrf=" + token)));
        assertEquals(PRODUCTION + "/owner/console", location(post(owner, "/owner/bind-domain",
                "tenantId=practicecafe&customDomain=orders.example.com&_csrf=" + token)));
    }
}
