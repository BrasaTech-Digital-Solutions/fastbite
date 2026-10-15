package es.brasatech.fastbite;

import es.brasatech.fastbite.application.tenant.OwnerSetupPort;
import es.brasatech.fastbite.application.tenant.OwnerSetupService;
import es.brasatech.fastbite.config.DemoDataInitializer;
import es.brasatech.fastbite.security.SecurityDataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:owner-flow;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.jakarta.persistence.jdbc.url=jdbc:h2:mem:owner-flow;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "fastbite.public-url=http://localhost:8080", "spring.mail.password=", "management.health.mail.enabled=false",
        "fastbite.migrations.mode=migrate", "spring.jpa.hibernate.ddl-auto=none", "spring.session.jdbc.initialize-schema=never", "stripe.secret.key=", "stripe.webhook.secret=", "stripe.connected-account="})
class OwnerSetupFlowTest {
    @Value("${local.server.port}") int port;
    @Autowired OwnerSetupService setup;
    @Autowired DataSource dataSource;
    @Autowired ApplicationContext context;
    @MockitoBean JavaMailSender sender;

    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager()).build(); }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(HttpClient client, String path, String data) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(data)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String csrf(String html) {
        var match = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertTrue(match.find(), "CSRF form field must be present");
        return URLEncoder.encode(match.group(1), StandardCharsets.UTF_8);
    }

    @Test void emailedLinkSetsPasswordAndAllowsOwnerLoginWithoutDemoAccounts() throws Exception {
        assertTrue(context.getBeansOfType(SecurityDataInitializer.class).isEmpty());
        assertTrue(context.getBeansOfType(DemoDataInitializer.class).isEmpty());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM public.users")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
        setup.invite(new OwnerSetupPort.Invitation("flow-checkout", "flowtenant", "flowowner", "Flow Owner", "owner@example.test", "RESTAURANT"));
        var email = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(email.capture());
        assertArrayEquals(new String[]{"owner@example.test"}, email.getValue().getTo());
        var tokenMatch = Pattern.compile("token=([A-Za-z0-9_-]{43})").matcher(email.getValue().getText());
        assertTrue(tokenMatch.find());
        String token = tokenMatch.group(1);
        var guest = client();
        var loginBefore = get(guest, "/login");
        var rejected = post(guest, "/login", "username=flowowner&password=Tempflowtenant2026!&_csrf=" + csrf(loginBefore.body()));
        assertTrue(rejected.headers().firstValue("Location").orElse("").contains("error"));
        var form = get(guest, "/set-password?token=" + token);
        assertEquals(200, form.statusCode());
        assertEquals("no-referrer", form.headers().firstValue("Referrer-Policy").orElse(""));
        var completed = post(guest, "/set-password", "token=" + token + "&password=strong-local-test-password&confirmation=strong-local-test-password&_csrf=" + csrf(form.body()));
        assertEquals(200, completed.statusCode());
        assertTrue(completed.body().contains("Your password is set"));
        assertEquals(400, get(guest, "/set-password?token=" + token).statusCode());
        for (String loginPath : new String[]{"/login", "/flowtenant/login"}) {
            var owner = client();
            var login = get(owner, loginPath);
            var signedIn = post(owner, "/login" + (loginPath.startsWith("/flowtenant") ? "?tenantId=flowtenant" : ""),
                    "username=flowowner&password=strong-local-test-password&_csrf=" + csrf(login.body()));
            assertEquals(302, signedIn.statusCode());
            assertFalse(signedIn.headers().firstValue("Location").orElse("").contains("error"));
            assertEquals(200, get(owner, "/owner/console").statusCode());
        }
        setup.invite(new OwnerSetupPort.Invitation("flow-checkout", "flowtenant", "flowowner", "Flow Owner", "owner@example.test", "RESTAURANT"));
        verify(sender, times(1)).send(any(SimpleMailMessage.class));
    }
}
