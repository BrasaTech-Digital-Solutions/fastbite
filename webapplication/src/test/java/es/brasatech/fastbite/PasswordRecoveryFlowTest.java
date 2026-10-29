package es.brasatech.fastbite;

import es.brasatech.fastbite.application.mail.OwnerSetupMailPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:recovery-flow;DB_CLOSE_DELAY=-1", "spring.jpa.properties.jakarta.persistence.jdbc.url=jdbc:h2:mem:recovery-flow;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "fastbite.public-url=http://localhost:8080",
    "fastbite.environment=production", "fastbite.link.standalone=true", "spring.mail.password=", "management.health.mail.enabled=false",
    "fastbite.migrations.mode=migrate", "spring.jpa.hibernate.ddl-auto=none", "spring.session.jdbc.initialize-schema=never"})
class PasswordRecoveryFlowTest {
    @Value("${local.server.port}") int port;
    @Autowired es.brasatech.fastbite.application.tenant.PendingSignupPort pending;
    @Autowired es.brasatech.fastbite.application.tenant.PendingSignupService signup;
    @Autowired es.brasatech.fastbite.application.mail.AccountMailOutboxPort outbox;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @MockitoBean OwnerSetupMailPort mail;
    private HttpClient browser() { return HttpClient.newBuilder().cookieHandler(new CookieManager()).build(); }
    private HttpResponse<String> get(HttpClient c, String path) throws Exception { return c.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString()); }
    private HttpResponse<String> post(HttpClient c, String path, String body) throws Exception { return c.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()); }
    private String csrf(String html) { var m = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html); assertThat(m.find()).isTrue(); return URLEncoder.encode(m.group(1), StandardCharsets.UTF_8); }
    @Test void recoveryAnswersGenericallyAndEndsAllOwnerSessions() throws Exception {
        VerifiedSignupFixture.create(pending, signup, outbox, passwords, "recoverflow", "recoverowner", "Owner", "original password");
        var a = browser(); var b = browser(); var guest = browser();
        for (var c : new HttpClient[]{a, b}) assertThat(post(c, "/login", "username=recoverowner&password=original+password&_csrf=" + csrf(get(c, "/login").body())).statusCode()).isEqualTo(302);
        var unknown = post(guest, "/forgot-password", "identifier=missing%40example.test&_csrf=" + csrf(get(guest, "/forgot-password").body()));
        var known = post(guest, "/forgot-password", "identifier=recoverowner&_csrf=" + csrf(get(guest, "/forgot-password").body()));
        assertThat(unknown.statusCode()).isEqualTo(known.statusCode());
        assertThat(unknown.body()).contains("If an eligible account exists");
        assertThat(known.body()).contains("If an eligible account exists");
        assertThat(known.body()).isEqualTo(unknown.body());
        var link = ArgumentCaptor.forClass(String.class);
        verify(mail).sendPasswordReset(eq("recoverowner@example.test"), eq("recoverowner"), link.capture(), anyString());
        URI uri = URI.create(link.getValue()); String target = uri.getRawPath() + "?" + uri.getRawQuery();
        var reset = get(guest, target); assertThat(reset.statusCode()).isEqualTo(200);
        String token = uri.getRawQuery().substring("token=".length());
        var done = post(guest, "/reset-password", "token=" + token + "&password=replacement+password&confirmation=replacement+password&_csrf=" + csrf(reset.body()));
        assertThat(done.headers().firstValue("Location")).contains("/login?reset");
        assertThat(get(guest, target).statusCode()).isEqualTo(400);
        for (var c : new HttpClient[]{a, b}) assertThat(get(c, "/owner/console").headers().firstValue("Location")).contains("/login");
        assertThat(post(a, "/login", "username=recoverowner&password=replacement+password&_csrf=" + csrf(get(a, "/login").body())).headers().firstValue("Location")).hasValueSatisfying(location -> assertThat(location).contains("/owner/console"));
    }
}
