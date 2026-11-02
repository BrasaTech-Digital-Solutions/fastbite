package es.brasatech.fastbite;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** HTML compression must not expose an unmasked, repeated session CSRF token. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:csrf-masking;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.jakarta.persistence.jdbc.url=jdbc:h2:mem:csrf-masking;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.mail.password=",
        "fastbite.migrations.mode=migrate", "spring.jpa.hibernate.ddl-auto=none", "spring.session.jdbc.initialize-schema=never",
        "stripe.secret.key=", "stripe.webhook.secret=", "stripe.platform.webhook.secret=", "stripe.restaurant-price-id=",
        "stripe.connected-account="})
class CsrfMaskingFlowTest {
    @Value("${local.server.port}") int port;

    @Test void sameSessionGetsFreshXorMasksAndBothResolveToSameSecret() throws Exception {
        CookieManager cookies = new CookieManager();
        HttpClient browser = HttpClient.newBuilder().cookieHandler(cookies).build();
        URI login = URI.create("http://localhost:" + port + "/login");
        HttpRequest request = HttpRequest.newBuilder(login).GET().build();
        HttpResponse<String> first = browser.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> second = browser.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(cookies.getCookieStore().getCookies()).isNotEmpty();
        String a = token(first.body());
        String b = token(second.body());
        assertThat(a.equals(b)).as("same-session rendered tokens use distinct masks").isFalse();
        assertThat(MessageDigest.isEqual(unmask(a), unmask(b)))
                .as("both masks decode to the same session CSRF secret").isTrue();
        String form = "username=missing&password=wrong&_csrf=" + URLEncoder.encode(a, StandardCharsets.UTF_8);
        HttpResponse<Void> post = browser.send(HttpRequest.newBuilder(login)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(post.statusCode()).as("masked token is accepted by the CSRF filter").isEqualTo(302);
    }

    private static String token(String html) {
        var match = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(match.find()).as("a hidden CSRF field is rendered").isTrue();
        return match.group(1);
    }

    private static byte[] unmask(String value) {
        byte[] bytes = Base64.getUrlDecoder().decode(value);
        assertThat(bytes.length % 2).isZero();
        byte[] result = new byte[bytes.length / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) (bytes[i] ^ bytes[i + result.length]);
        return result;
    }
}
