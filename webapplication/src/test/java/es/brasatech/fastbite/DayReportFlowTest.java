package es.brasatech.fastbite;

import es.brasatech.fastbite.application.fiscal.FiscalTicketRepository;
import es.brasatech.fastbite.application.tenant.SubscriptionGateway;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.PaymentMethod;
import es.brasatech.fastbite.domain.fiscal.TicketCancellation;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.fiscal.VatBreakdown;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The end-of-day report and the cash check, from the day's real tickets; nothing counted is stored. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:day-report-flow;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.jakarta.persistence.jdbc.url=jdbc:h2:mem:day-report-flow;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.mail.password=",
        "fastbite.migrations.mode=migrate", "spring.jpa.hibernate.ddl-auto=none", "spring.session.jdbc.initialize-schema=never",
        "stripe.secret.key=", "stripe.webhook.secret=", "stripe.platform.webhook.secret=", "stripe.restaurant-price-id=",
        "stripe.connected-account="})
class DayReportFlowTest {

    @Value("${local.server.port}") int port;
    @Autowired FiscalTicketRepository tickets;
    @Autowired TransactionTemplate transactions;
    @MockitoBean SubscriptionGateway gateway;

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    private HttpResponse<String> get(HttpClient c, String path) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient c, String path, String body) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String csrf(String html) {
        var m = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(m.find()).isTrue();
        return URLEncoder.encode(m.group(1), StandardCharsets.UTF_8);
    }

    static int number;

    /** A training ticket issued now (the default environment is the sandbox). */
    private static FiscalTicket ticket(TicketKind kind, String series, PaymentMethod method, String total, String tip,
            String corrects) {
        String id = UUID.randomUUID().toString();
        BigDecimal gross = new BigDecimal(total);
        number++;
        return new FiscalTicket(id, kind, series, number, series + "-" + String.format("%06d", number), true,
                OffsetDateTime.now(ZoneId.of("Europe/Madrid")).withNano(0), "carla",
                new FiscalTicket.Issuer("Ana Garcia", "Day Cafe", "12345678Z", null, null, null, null, null), null, List.of(),
                BigDecimal.ZERO, gross, VatBreakdown.of(List.of(new VatBreakdown.Amount(gross, new BigDecimal("10")))),
                new BigDecimal(tip), method, null, null,
                corrects == null ? null : new FiscalTicket.Correction(corrects, "TEST-DC-T26-000001", null, "wrong dish"),
                List.of(id), "1", null, null);
    }

    private void issue(String tenant, FiscalTicket ticket, boolean cancel) {
        TenantContext.setCurrentTenant(tenant);
        try {
            transactions.executeWithoutResult(status -> {
                tickets.add(ticket);
                if (cancel) {
                    tickets.cancel(new TicketCancellation(ticket.id(), ticket.issuedAt(), "carla", "wrong table"));
                }
            });
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void theDayIsWorkedOutFromItsTicketsAndTheCashCheckStartsFromTheOwnersFloat() throws Exception {
        var owner = client();
        post(owner, "/signup", "acceptTerms=true&tenantId=daycafe&username=dayowner&fullName=Test+Owner&password=local-test-password&_csrf="
                + csrf(get(owner, "/signup").body()));
        post(owner, "/login", "username=dayowner&password=local-test-password&_csrf=" + csrf(get(owner, "/login").body()));

        var sale = ticket(TicketKind.SIMPLIFIED, "TEST-DC-T26", PaymentMethod.CASH, "11.00", "0.50", null);
        issue("daycafe", sale, false);
        issue("daycafe", ticket(TicketKind.SIMPLIFIED, "TEST-DC-T26", PaymentMethod.CARD, "22.00", "0.00", null), false);
        issue("daycafe", ticket(TicketKind.SIMPLIFIED, "TEST-DC-T26", PaymentMethod.CASH, "30.00", "0.00", null), true);
        issue("daycafe", ticket(TicketKind.RECTIFYING, "TEST-DC-R26", PaymentMethod.CASH, "-5.50", "0.00", sale.id()), false);

        // The owner sets the starting cash once, in the location's settings
        var settings = get(owner, "/owner/console?view=location-settings&location=daycafe").body();
        assertThat(settings).contains("Cash at the start of the day");
        post(owner, "/owner/workspace/daycafe/cash-float", "cashFloat=150,50&_csrf=" + csrf(settings));
        assertThat(get(owner, "/owner/console?view=location-settings&location=daycafe").body()).contains("value=\"150.50\"");

        var cash = new ObjectMapper().readTree(get(owner, "/daycafe/counter/api/cash").body());
        assertThat(cash.get("cashFloat").decimalValue()).isEqualByComparingTo("150.50");
        assertThat(cash.get("cashSales").decimalValue()).as("the cancelled ticket counts nothing").isEqualByComparingTo("11.50");
        assertThat(cash.get("cashRefunds").decimalValue()).isEqualByComparingTo("-5.50");
        assertThat(cash.get("cashNet").decimalValue()).isEqualByComparingTo("6.00");
        assertThat(cash.get("asOf").asString()).matches("\\d\\d:\\d\\d");

        var report = get(owner, "/daycafe/counter/day-report");
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(report.body()).contains("End-of-day report", "Business details missing", "TEST-DC-T26-000001", "TEST-DC-T26-000003",
                "TEST-DC-R26-000004", "Cancelled tickets", "wrong table", "Cash check",
                "27,50", // net: 11.00 + 22.00 - 5.50
                "28,00"); // taken: 11.50 cash - 5.50 refunded + 22.00 card
        // Dates in links are ISO, whatever the language of the page
        String today = java.time.LocalDate.now(ZoneId.of("Europe/Madrid")).toString();
        assertThat(get(owner, "/daycafe/counter/tickets").body()).contains("End-of-day report", "Check the cash", "cashCheckModal",
                "/daycafe/counter/day-report?date=" + today, "/daycafe/counter/api/cash?date=" + today);
        assertThat(get(owner, "/daycafe/counter").body()).contains("Check the cash", "cashCheckModal",
                "/daycafe/counter/api/cash?date=" + today);
        assertThat(report.body()).contains("/daycafe/counter/api/cash?date=" + today, "value=\"" + today + "\"");

        // The kitchen has no business with the takings
        var token = csrf(get(owner, "/owner/console?view=team&location=daycafe").body());
        post(owner, "/owner/locations/daycafe/users", "username=daycook&fullName=Day+Cook&password=local-staff-password&role=COOK&_csrf=" + token);
        var cook = client();
        post(cook, "/login?tenantId=daycafe", "username=daycook&password=local-staff-password&_csrf=" + csrf(get(cook, "/daycafe/login").body()));
        assertThat(get(cook, "/daycafe/counter/day-report").statusCode()).isEqualTo(403);
        assertThat(get(cook, "/daycafe/counter/api/cash").statusCode()).isEqualTo(403);
        assertThat(get(cook, "/daycafe/counter").body()).doesNotContain("cashCheckModal");
    }
}
