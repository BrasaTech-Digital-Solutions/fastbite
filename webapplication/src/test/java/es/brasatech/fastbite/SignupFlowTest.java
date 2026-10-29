package es.brasatech.fastbite;

import es.brasatech.fastbite.application.mail.OwnerSetupMailPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
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
        "spring.datasource.url=jdbc:h2:mem:signup-flow;DB_CLOSE_DELAY=-1", "spring.jpa.properties.jakarta.persistence.jdbc.url=jdbc:h2:mem:signup-flow;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "fastbite.public-url=http://localhost:8080",
        "fastbite.environment=production", "fastbite.link.standalone=true", "spring.mail.password=", "management.health.mail.enabled=false",
        "fastbite.migrations.mode=migrate", "spring.jpa.hibernate.ddl-auto=none", "spring.session.jdbc.initialize-schema=never"})
class SignupFlowTest {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean OwnerSetupMailPort mail;
    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager()).build(); }
    private HttpResponse<String> get(HttpClient c, String path) throws Exception { return c.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString()); }
    private HttpResponse<String> post(HttpClient c, String path, String body) throws Exception { return c.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()); }
    private String csrf(String html) { var m = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html); assertThat(m.find()).isTrue(); return URLEncoder.encode(m.group(1), StandardCharsets.UTF_8); }

    @Test void correctionReplacesLinkAndAccountExistsOnlyAfterVerification() throws Exception {
        var browser = client(); String token = csrf(get(browser, "/signup").body());
        var response = post(browser, "/signup", "tenantId=verifyflow&username=verifyowner&fullName=Owner&email=wrong%40example.test&password=local-test-password&acceptTerms=true&_csrf=" + token);
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_locations WHERE tenant_id='verifyflow'", Integer.class)).isZero();
        var oldLink = ArgumentCaptor.forClass(String.class);
        verify(mail).sendSignupVerification(eq("wrong@example.test"), eq("verifyowner"), oldLink.capture(), anyString());
        var check = get(browser, "/signup/check-email");
        assertThat(check.body()).contains("wrong@example.test");
        var login = get(browser, "/login");
        assertThat(post(browser, "/login", "username=verifyowner&password=local-test-password&_csrf=" + csrf(login.body())).headers().firstValue("Location").orElse("")).contains("error");
        assertThat(post(browser, "/signup/check-email/change", "email=right%40example.test&_csrf=" + csrf(check.body())).statusCode()).isEqualTo(302);
        var newLink = ArgumentCaptor.forClass(String.class);
        verify(mail).sendSignupVerification(eq("right@example.test"), eq("verifyowner"), newLink.capture(), anyString());
        assertThat(get(browser, URI.create(oldLink.getValue()).getRawPath() + "?" + URI.create(oldLink.getValue()).getRawQuery()).statusCode()).isEqualTo(400);
        String verification = URI.create(newLink.getValue()).getRawPath() + "?" + URI.create(newLink.getValue()).getRawQuery();
        assertThat(get(browser, verification).statusCode()).isEqualTo(302);
        assertThat(get(browser, verification).statusCode()).isEqualTo(302);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_locations WHERE tenant_id='verifyflow'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT email FROM public.users WHERE username='verifyowner'", String.class)).isEqualTo("right@example.test");
        var other = client();
        var duplicate = post(other, "/signup", "tenantId=anotherflow&username=anotherowner&fullName=Other&email=right%40example.test&password=local-test-password&acceptTerms=true&_csrf=" + csrf(get(other, "/signup").body()));
        assertThat(duplicate.statusCode()).isEqualTo(302);
        assertThat(get(other, "/signup/check-email").body()).contains("right@example.test");
        verify(mail).sendSignupNotice(eq("right@example.test"), anyString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_locations WHERE tenant_id='anotherflow'", Integer.class)).isZero();
    }
}
