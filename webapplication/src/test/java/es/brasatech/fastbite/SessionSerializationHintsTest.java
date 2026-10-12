package es.brasatech.fastbite;

import es.brasatech.fastbite.application.tenant.SubscriptionGateway;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sessions are kept in the database, so everything in them is serialized. The native image can only
 * serialize classes registered for it: this signs in like a browser, collects every class stored in
 * the session and checks each one is registered in {@link es.brasatech.fastbite.config.WebAdapterHints}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:session-hints;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.jakarta.persistence.jdbc.url=jdbc:h2:mem:session-hints;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.mail.password=",
        "fastbite.migrations.mode=migrate", "spring.jpa.hibernate.ddl-auto=none",
        "stripe.secret.key=", "stripe.webhook.secret=", "stripe.platform.webhook.secret=", "stripe.restaurant-price-id=",
        "stripe.connected-account="})
@ActiveProfiles("jpa")
class SessionSerializationHintsTest {

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SubscriptionGateway gateway;

    private HttpResponse<String> get(HttpClient c, String path) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient c, String path, String body) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String csrf(String html) {
        var m = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        m.find();
        return URLEncoder.encode(m.group(1), StandardCharsets.UTF_8);
    }

    @Test
    void everyClassOfASignedInSessionCanBeSerializedInTheNativeImage() throws Exception {
        var owner = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        // A protected page first, so a saved request is kept in the session
        get(owner, "/owner/console");
        post(owner, "/signup", "tenantId=probecafe&username=probeowner&fullName=Probe&password=local-probe-password&_csrf="
                + csrf(get(owner, "/signup").body()));
        get(owner, "/owner/console");
        post(owner, "/login", "username=probeowner&password=local-probe-password&_csrf=" + csrf(get(owner, "/login").body()));
        get(owner, "/owner/console");
        get(owner, "/probecafe/counter");
        get(owner, "/probecafe/backoffice");
        // A redirect that carries a notice keeps it in the session (flash attributes) until the next page
        String token = csrf(get(owner, "/owner/console?view=location-settings&section=legal&location=probecafe").body());
        post(owner, "/owner/workspace/probecafe/legal", "legalName=Probe&_csrf=" + token);
        post(owner, "/owner/workspace/probecafe/setup", "action=dismiss&_csrf=" + token);

        // Read before the next page takes the notice out of the session
        TreeSet<String> classes = new TreeSet<>();
        var rows = jdbc.queryForList("SELECT ATTRIBUTE_NAME, ATTRIBUTE_BYTES FROM PUBLIC.SPRING_SESSION_ATTRIBUTES");
        assertThat(rows).as("sessions are stored in the database").extracting(row -> row.get("ATTRIBUTE_NAME"))
                .contains("SPRING_SECURITY_CONTEXT");
        for (var row : rows) {
            try (InputStream in = new ByteArrayInputStream((byte[]) row.get("ATTRIBUTE_BYTES"));
                 ObjectInputStream objects = new ObjectInputStream(in) {
                     @Override
                     protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
                         classes.add(desc.getName());
                         return super.resolveClass(desc);
                     }
                 }) {
                objects.readObject();
            }
        }
        RuntimeHints hints = new RuntimeHints();
        new es.brasatech.fastbite.config.WebAdapterHints().registerHints(hints, getClass().getClassLoader());
        assertThat(classes).as("classes stored in a session but not registered for native serialization")
                .filteredOn(type -> !RuntimeHintsPredicates.serialization().onType(TypeReference.of(type)).test(hints))
                .isEmpty();
    }
}
