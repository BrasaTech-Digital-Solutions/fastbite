package es.brasatech.fastbite;

import es.brasatech.fastbite.application.fiscal.FiscalTicketRepository;
import es.brasatech.fastbite.application.tenant.SubscriptionGateway;
import es.brasatech.fastbite.application.verifactu.VerifactuRepository;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.PaymentMethod;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.fiscal.VatBreakdown;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.verifactu.Verifactu;
import es.brasatech.fastbite.domain.verifactu.VerifactuRecord;
import es.brasatech.fastbite.domain.verifactu.VerifactuStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** An owner sends a refused billing record again, as a subsanación, from the owner console. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:verifactu-amend-flow;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.jakarta.persistence.jdbc.url=jdbc:h2:mem:verifactu-amend-flow;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.mail.password=",
        "fastbite.migrations.mode=migrate", "spring.jpa.hibernate.ddl-auto=none", "spring.session.jdbc.initialize-schema=never",
        "stripe.secret.key=", "stripe.webhook.secret=", "stripe.platform.webhook.secret=", "stripe.restaurant-price-id=",
        "stripe.connected-account="})
class VerifactuAmendFlowTest {

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired FiscalTicketRepository tickets;
    @Autowired VerifactuRepository records;
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

    private String csrf(String html) {
        var m = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(m.find()).isTrue();
        return URLEncoder.encode(m.group(1), StandardCharsets.UTF_8);
    }

    /** A training ticket (the default environment is the sandbox) whose billing record the AEAT refused. */
    private VerifactuRecord refusedRecord(String tenant) {
        TenantContext.setCurrentTenant(tenant);
        try {
            return transactions.execute(status -> {
                String id = UUID.randomUUID().toString();
                var issuedAt = OffsetDateTime.parse("2026-10-09T13:00:00+02:00");
                tickets.add(new FiscalTicket(id, TicketKind.SIMPLIFIED, "TEST-AC-T26", 1, "TEST-AC-T26-000001", true, issuedAt,
                        "carla", new FiscalTicket.Issuer("Ana Garcia", null, "12345678Z", null, null, null, null, null),
                        null, List.of(), BigDecimal.ZERO, new BigDecimal("11.00"), VatBreakdown.EMPTY, BigDecimal.ZERO,
                        PaymentMethod.CARD, null, null, null, List.of(id), null, null, null));
                records.lockChain(VerifactuRecord.TRAINING_CHAIN);
                String fingerprint = Verifactu.fingerprint("12345678Z", "TEST-AC-T26-000001", issuedAt.toLocalDate(), "F2",
                        new BigDecimal("1.00"), new BigDecimal("11.00"), null, issuedAt);
                var record = records.append(new VerifactuRecord(UUID.randomUUID().toString(), id,
                        VerifactuRecord.TRAINING_CHAIN, 1, "F2", "12345678Z", "Ana Garcia", "TEST-AC-T26-000001",
                        issuedAt.toLocalDate(), issuedAt, new BigDecimal("1.00"), new BigDecimal("11.00"), null,
                        fingerprint, "https://qr", null, null, true, VerifactuRecord.Submission.PENDING));
                records.updateSubmission(record.id(), new VerifactuRecord.Submission(VerifactuStatus.REJECTED, 1,
                        "4116: El NIF no está identificado en el censo", null, issuedAt.plusMinutes(1)));
                return record;
            });
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void anOwnerSendsARefusedRecordAgainAsASubsanacion() throws Exception {
        var owner = client();
        post(owner, "/signup", "tenantId=amendcafe&username=amendowner&fullName=Test+Owner&password=local-test-password&_csrf="
                + csrf(get(owner, "/signup").body()));
        post(owner, "/login", "username=amendowner&password=local-test-password&_csrf=" + csrf(get(owner, "/login").body()));
        var refused = refusedRecord("amendcafe");

        var legal = get(owner, "/owner/console?view=location-settings&section=legal&location=amendcafe");
        assertThat(legal.statusCode()).isEqualTo(200);
        assertThat(legal.body()).contains("Records to fix and send again", "TEST-AC-T26-000001", "4116: El NIF no",
                "/owner/workspace/amendcafe/verifactu/records/" + refused.id() + "/amend", "Send again corrected");

        String path = "/owner/workspace/amendcafe/verifactu/records/" + refused.id() + "/amend";
        assertThat(post(owner, path, "").statusCode()).as("needs the CSRF token").isEqualTo(403);
        var sent = post(owner, path, "_csrf=" + csrf(legal.body()));
        assertThat(sent.statusCode()).isEqualTo(302);

        assertThat(jdbc.queryForObject("SELECT status FROM tenant_amendcafe.verifactu_records WHERE id = ?", String.class,
                refused.id())).isEqualTo("AMENDED");
        var amendment = jdbc.queryForMap("SELECT * FROM tenant_amendcafe.verifactu_records WHERE amends_record_id = ?",
                refused.id());
        assertThat(amendment.get("STATUS")).isEqualTo("PENDING");
        assertThat(amendment.get("AMENDS_AFTER_REJECTION")).isEqualTo(true);
        assertThat(amendment.get("INVOICE_NUMBER")).isEqualTo("TEST-AC-T26-000001");
        assertThat(amendment.get("SEQUENCE_NUMBER")).isEqualTo(2);
        assertThat(amendment.get("TICKET_ID")).isNull();
        assertThat(amendment.get("INVOICE_TICKET_ID")).isEqualTo(refused.ticketId());
        assertThat(amendment.get("PREVIOUS_FINGERPRINT")).isEqualTo(refused.fingerprint());

        var after = get(owner, "/owner/console?view=location-settings&section=legal&location=amendcafe").body();
        assertThat(after).contains("Correction record created").doesNotContain("Send again corrected");
        assertThat(post(owner, path, "_csrf=" + csrf(after)).headers().firstValue("location").orElse(""))
                .contains("section=legal");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_amendcafe.verifactu_records", Integer.class))
                .as("amended once only").isEqualTo(2);
        assertThat(post(owner, "/owner/workspace/notowned/verifactu/records/" + refused.id() + "/amend",
                "_csrf=" + csrf(after)).statusCode()).isEqualTo(404);
    }
}
