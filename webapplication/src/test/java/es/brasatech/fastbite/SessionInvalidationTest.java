package es.brasatech.fastbite;

import es.brasatech.fastbite.application.mail.AccountMailOutboxPort;
import es.brasatech.fastbite.application.mail.OwnerSetupMailPort;
import es.brasatech.fastbite.application.tenant.PendingSignupPort;
import es.brasatech.fastbite.application.tenant.PendingSignupService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:session-invalidation;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.jakarta.persistence.jdbc.url=jdbc:h2:mem:session-invalidation;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "fastbite.public-url=http://localhost:8080",
        "fastbite.environment=production", "fastbite.link.standalone=true",
        "spring.mail.password=", "management.health.mail.enabled=false",
        "fastbite.migrations.mode=migrate", "spring.jpa.hibernate.ddl-auto=none",
        "spring.session.jdbc.initialize-schema=never"})
class SessionInvalidationTest {

    @Value("${local.server.port}") int port;
    @Autowired PendingSignupPort pending;
    @Autowired PendingSignupService signup;
    @Autowired AccountMailOutboxPort outbox;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcIndexedSessionRepository sessionRepository;
    @MockitoBean OwnerSetupMailPort mail;

    private HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String csrf(String html) {
        var matcher = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(matcher.find()).isTrue();
        return URLEncoder.encode(matcher.group(1), StandardCharsets.UTF_8);
    }

    @Test
    void jdbcSessionRepositoryUsesOrderedPrincipalNameQuery() {
        String sql = (String) ReflectionTestUtils.getField(sessionRepository, "listSessionsByPrincipalNameQuery");
        assertThat(sql)
                .contains("FROM PUBLIC.SPRING_SESSION S")
                .contains("LEFT JOIN PUBLIC.SPRING_SESSION_ATTRIBUTES SA")
                .contains("ORDER BY S.SESSION_ID");
    }

    @Test
    void passwordResetAndPasswordChangeEndOtherOwnerSessionsWithMultipleAttributes() throws Exception {
        VerifiedSignupFixture.create(pending, signup, outbox, passwords,
                "sessioninv", "sessionowner", "Session Owner", "original password");

        var first = browser();
        var second = browser();
        for (var client : new HttpClient[]{first, second}) {
            var login = post(client, "/login",
                    "username=sessionowner&password=original+password&_csrf=" + csrf(get(client, "/login").body()));
            assertThat(login.statusCode()).isEqualTo(302);
            // Visit /owner/console so both sessions persist SPRING_SECURITY_CONTEXT, ownerEmailVerified, and CSRF attributes.
            assertThat(get(client, "/owner/console").statusCode()).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM PUBLIC.SPRING_SESSION WHERE PRINCIPAL_NAME = 'sessionowner'", Integer.class))
                .isEqualTo(2);

        // 1. Password change while signed in keeps the current session and deletes the other session.
        String settingsHtml = get(first, "/owner/console?view=settings").body();
        var changed = post(first, "/owner/account/password",
                "currentPassword=original+password&password=updated+password&confirmation=updated+password&_csrf=" + csrf(settingsHtml));
        assertThat(changed.headers().firstValue("Location")).contains("/owner/console?view=settings&passwordChanged");
        assertThat(get(first, "/owner/console").statusCode()).isEqualTo(200);
        assertThat(get(second, "/owner/console").headers().firstValue("Location"))
                .hasValueSatisfying(loc -> assertThat(loc).contains("/login"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM PUBLIC.SPRING_SESSION WHERE PRINCIPAL_NAME = 'sessionowner'", Integer.class))
                .isEqualTo(1);

        // 2. Sign in the second browser with the updated password, then trigger password reset: both sessions must end.
        assertThat(post(second, "/login",
                "username=sessionowner&password=updated+password&_csrf=" + csrf(get(second, "/login").body())).statusCode())
                .isEqualTo(302);
        assertThat(get(second, "/owner/console").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM PUBLIC.SPRING_SESSION WHERE PRINCIPAL_NAME = 'sessionowner'", Integer.class))
                .isEqualTo(2);

        var guest = browser();
        post(guest, "/forgot-password", "identifier=sessionowner&_csrf=" + csrf(get(guest, "/forgot-password").body()));
        var link = ArgumentCaptor.forClass(String.class);
        verify(mail).sendPasswordReset(eq("sessionowner@example.test"), eq("sessionowner"), link.capture(), anyString());
        URI uri = URI.create(link.getValue());
        String resetPath = uri.getRawPath() + "?" + uri.getRawQuery();
        var resetForm = get(guest, resetPath);
        assertThat(resetForm.statusCode()).isEqualTo(200);
        String token = uri.getRawQuery().substring("token=".length());
        var resetDone = post(guest, "/reset-password",
                "token=" + token + "&password=final+password&confirmation=final+password&_csrf=" + csrf(resetForm.body()));
        assertThat(resetDone.headers().firstValue("Location")).contains("/login?reset");

        for (var client : new HttpClient[]{first, second}) {
            assertThat(get(client, "/owner/console").headers().firstValue("Location"))
                    .hasValueSatisfying(loc -> assertThat(loc).contains("/login"));
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM PUBLIC.SPRING_SESSION WHERE PRINCIPAL_NAME = 'sessionowner'", Integer.class))
                .isZero();
    }
}
