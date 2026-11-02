package es.brasatech.fastbite;

import es.brasatech.fastbite.application.tenant.SubscriptionGateway;
import es.brasatech.fastbite.application.tenant.PendingSignupPort;
import es.brasatech.fastbite.application.tenant.PendingSignupService;
import es.brasatech.fastbite.application.mail.AccountMailOutboxPort;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests the actual embedded server, because MockMvc does not apply Tomcat compression. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:compression-flow;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.jakarta.persistence.jdbc.url=jdbc:h2:mem:compression-flow;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.mail.password=",
        "fastbite.migrations.mode=migrate", "spring.jpa.hibernate.ddl-auto=none", "spring.session.jdbc.initialize-schema=never",
        "stripe.secret.key=", "stripe.webhook.secret=", "stripe.platform.webhook.secret=", "stripe.restaurant-price-id=", "stripe.connected-account="})
@Import(CompressionFlowTest.SessionConfig.class)
class CompressionFlowTest {
    @Value("${local.server.port}") int port;
    @MockitoBean SubscriptionGateway gateway;
    @Autowired PendingSignupPort pendingSignup;
    @Autowired PendingSignupService signupService;
    @Autowired AccountMailOutboxPort accountOutbox;
    @Autowired PasswordEncoder passwords;

    @TestConfiguration
    static class SessionConfig {
        @Bean TestSessionController testSessionController() { return new TestSessionController(); }
    }

    @RestController
    static class TestSessionController {
        @GetMapping("/signup/compression-session")
        String session(HttpSession session) { session.setAttribute("orderId", "synthetic-order"); return "ready"; }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(5));
    }

    @Test void htmlAndCssNegotiateGzipAndPrivateHtmlStaysNoStore() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        for (String path : new String[]{"/", "/css/platform.css"}) {
            HttpResponse<byte[]> compressed = client.send(request(path).header("Accept-Encoding", "gzip").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(compressed.statusCode()).isEqualTo(200);
            assertThat(compressed.headers().firstValue("Content-Encoding")).as(path + " headers " + compressed.headers()).contains("gzip");
            assertThat(compressed.headers().firstValue("Vary").orElse("")).containsIgnoringCase("Accept-Encoding");
            try (var input = new GZIPInputStream(new ByteArrayInputStream(compressed.body()))) {
                assertThat(input.readAllBytes().length).isGreaterThan(1024);
            }
            HttpResponse<byte[]> identity = client.send(request(path).header("Accept-Encoding", "identity").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(identity.headers().firstValue("Content-Encoding")).isEmpty();
            if (path.equals("/")) assertThat(compressed.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        }
    }

    @Test void realLiveOrderStreamRemainsUncompressedAndFlushesImmediately() throws Exception {
        VerifiedSignupFixture.create(pendingSignup, signupService, accountOutbox, passwords,
                "compressiontest", "compressionowner", "Owner", "local-test-password");
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        assertThat(client.send(request("/signup/compression-session").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        var response = client.send(request("/compressiontest/api/order-status/live").header("Accept-Encoding", "gzip").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
        assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
        try (var body = response.body(); var executor = Executors.newSingleThreadExecutor()) {
            var line = executor.submit(() -> new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8)).readLine());
            assertThat(line.get(3, TimeUnit.SECONDS)).contains("connected");
        }
    }
}
