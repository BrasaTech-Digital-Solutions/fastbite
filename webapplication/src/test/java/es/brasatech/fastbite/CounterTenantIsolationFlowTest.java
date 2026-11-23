package es.brasatech.fastbite;

import es.brasatech.fastbite.application.mail.AccountMailOutboxPort;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.application.tenant.PendingSignupPort;
import es.brasatech.fastbite.application.tenant.PendingSignupService;
import es.brasatech.fastbite.application.tenant.SubscriptionGateway;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.ServiceType;
import es.brasatech.fastbite.domain.table.Table;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the real HTTP routing and persistence against two separate tenant schemas. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:missing-resource-http;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.jakarta.persistence.jdbc.url=jdbc:h2:mem:missing-resource-http;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.mail.password=",
        "fastbite.migrations.mode=migrate", "spring.jpa.hibernate.ddl-auto=none", "spring.session.jdbc.initialize-schema=never",
        "stripe.secret.key=", "stripe.webhook.secret=", "stripe.platform.webhook.secret=", "stripe.restaurant-price-id=",
        "stripe.connected-account="})
class CounterTenantIsolationFlowTest {
    @Value("${local.server.port}") int port;
    @Autowired PendingSignupPort pendingSignup;
    @Autowired PendingSignupService signupService;
    @Autowired AccountMailOutboxPort accountOutbox;
    @Autowired PasswordEncoder passwords;
    @Autowired OrderService orders;
    @Autowired TableService tables;
    @MockitoBean SubscriptionGateway gateway;

    private HttpResponse<String> request(HttpClient c, String method, String path, String contentType, String body, String token)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (contentType != null) builder.header("Content-Type", contentType);
        if (token != null) builder.header("X-CSRF-TOKEN", token);
        if (method.equals("GET")) builder.GET();
        else builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        return c.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String csrf(String html) {
        var m = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(m.find()).as("CSRF field").isTrue();
        return m.group(1);
    }

    @Test
    void foreignOrdersAndTablesAreNotFoundAndRemainUnchanged() throws Exception {
        VerifiedSignupFixture.create(pendingSignup, signupService, accountOutbox, passwords,
                "notfounda", "notfoundownera", "Owner A", "local-test-password");
        VerifiedSignupFixture.create(pendingSignup, signupService, accountOutbox, passwords,
                "notfoundb", "notfoundownerb", "Owner B", "local-test-password");
        String otherOrder;
        String otherTable;
        es.brasatech.fastbite.domain.order.Order originalOrder;
        Table originalTable;
        TenantContext.setCurrentTenant("notfoundb");
        try {
            originalOrder = orders.createOrder(List.of(), 1, OrderPaymentStatus.UNPAID, OrderChannel.COUNTER,
                    "en", "cashier", "Marta", ServiceType.TAKEAWAY);
            originalTable = tables.create(new Table("B table", 4));
            otherOrder = originalOrder.id();
            otherTable = originalTable.id();
            originalOrder = orders.findById(otherOrder).orElseThrow();
            originalTable = tables.findById(otherTable).orElseThrow();
        } finally {
            TenantContext.clear();
        }
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String loginToken = csrf(request(client, "GET", "/login", null, "", null).body());
        var login = request(client, "POST", "/login", "application/x-www-form-urlencoded",
                "username=notfoundownera&password=local-test-password&_csrf=" + URLEncoder.encode(loginToken, StandardCharsets.UTF_8), null);
        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location").orElse("")).doesNotContain("error");
        String token = csrf(request(client, "GET", "/owner/console", null, "", null).body());
        String validBody = "{\"items\":[],\"tableId\":null,\"paymentMethod\":\"CASH\",\"paid\":false,\"customerName\":null}";
        for (String id : List.of("9e5dcc63-5ea9-4d46-ae35-916827a19f03", "bad!id", otherOrder)) {
            var update = request(client, "PUT", "/counter/api/orders/" + id + "?tenantId=notfounda", "application/json", validBody, token);
            var pay = request(client, "POST", "/counter/api/orders/" + id + "/pay?tenantId=notfounda", "application/x-www-form-urlencoded", "method=CASH", token);
            assertThat(update.statusCode()).as("counter update %s", id).isEqualTo(404);
            assertThat(pay.statusCode()).as("counter pay %s", id).isEqualTo(404);
        }
        assertThat(request(client, "POST", "/notfounda/api/order/" + otherOrder + "/next", null, "", token).statusCode())
                .as("kitchen order").isEqualTo(404);
        assertThat(request(client, "POST", "/counter/api/orders/" + otherOrder + "/cancel?tenantId=notfounda", "application/x-www-form-urlencoded", "", token).statusCode())
                .as("counter cancel").isEqualTo(404);
        assertThat(request(client, "GET", "/counter/api/tables/" + otherTable + "/active-orders?tenantId=notfounda", null, "", null).statusCode())
                .as("counter table").isEqualTo(404);
        assertThat(request(client, "POST", "/counter/api/tables/" + otherTable + "/status?tenantId=notfounda", "application/x-www-form-urlencoded", "status=AVAILABLE", token).statusCode())
                .as("counter table status").isEqualTo(404);
        TenantContext.setCurrentTenant("notfoundb");
        try {
            assertThat(orders.findById(otherOrder)).contains(originalOrder);
            assertThat(tables.findById(otherTable)).contains(originalTable);
        } finally {
            TenantContext.clear();
        }
    }
}
