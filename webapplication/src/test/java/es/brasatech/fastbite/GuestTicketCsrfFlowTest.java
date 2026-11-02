package es.brasatech.fastbite;

import es.brasatech.fastbite.application.fiscal.FiscalTicketRepository;
import es.brasatech.fastbite.application.mail.AccountMailOutboxPort;
import es.brasatech.fastbite.application.tenant.PendingSignupPort;
import es.brasatech.fastbite.application.tenant.PendingSignupService;
import es.brasatech.fastbite.application.tenant.SubscriptionGateway;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.PaymentMethod;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.fiscal.TicketLine;
import es.brasatech.fastbite.domain.fiscal.VatBreakdown;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.service.GuestTicketSignature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A first visit must create the CSRF session before the real servlet response commits. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:guest-ticket-csrf;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.jakarta.persistence.jdbc.url=jdbc:h2:mem:guest-ticket-csrf;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.mail.password=",
        "fastbite.migrations.mode=migrate", "spring.jpa.hibernate.ddl-auto=none", "spring.session.jdbc.initialize-schema=never",
        "stripe.secret.key=", "stripe.webhook.secret=", "stripe.platform.webhook.secret=", "stripe.restaurant-price-id=",
        "stripe.connected-account="})
class GuestTicketCsrfFlowTest {
    @Value("${local.server.port}") int port;
    @Autowired PendingSignupPort pendingSignup;
    @Autowired PendingSignupService signupService;
    @Autowired AccountMailOutboxPort accountOutbox;
    @Autowired PasswordEncoder passwords;
    @Autowired FiscalTicketRepository tickets;
    @Autowired TransactionTemplate transactions;
    @Autowired GuestTicketSignature signatures;
    @MockitoBean SubscriptionGateway gateway;

    @Test void firstVisitToLargeGuestTicketsCreatesSessionBeforeResponseCommits() throws Exception {
        String tenant = "invoicecsrf";
        VerifiedSignupFixture.create(pendingSignup, signupService, accountOutbox, passwords,
                tenant, "invoicecsrfowner", "Owner", "local-test-password");
        TicketLine line = new TicketLine("A long enough menu item to fill the response buffer", 1,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, List.of());
        List<TicketLine> lines = Collections.nCopies(180, line);
        FiscalTicket.Issuer issuer = new FiscalTicket.Issuer("Cafe", null, "12345678Z", "Street", "28001", "Madrid", null, null);
        FiscalTicket simplified = ticket("csrf-sale", TicketKind.SIMPLIFIED, issuer, lines);
        FiscalTicket full = ticket("csrf-full", TicketKind.FULL, issuer, lines);
        TenantContext.setCurrentTenant(tenant);
        try { transactions.executeWithoutResult(status -> { tickets.add(simplified); tickets.add(full); }); }
        finally { TenantContext.clear(); }

        HttpResponse<String> saleResponse = get(tenant, simplified.id());
        assertThat(saleResponse.statusCode()).isEqualTo(200);
        assertThat(saleResponse.body().length()).isGreaterThan(8192);
        assertThat(saleResponse.body()).contains("name=\"_csrf\"", "</form>", "Save or print", "</html>");
        assertThat(saleResponse.headers().allValues("Set-Cookie")).isNotEmpty();

        HttpResponse<String> fullResponse = get(tenant, full.id());
        assertThat(fullResponse.statusCode()).isEqualTo(200);
        assertThat(fullResponse.body()).contains("Save or print", "</html>", "name=\"_csrf\"", "name=\"email\"");
        assertThat(fullResponse.headers().allValues("Set-Cookie")).isNotEmpty();
    }

    private HttpResponse<String> get(String tenant, String id) throws Exception {
        String url = "http://localhost:" + port + "/" + tenant + "/ticket/" + id + "?s=" + signatures.sign(tenant, id);
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static FiscalTicket ticket(String id, TicketKind kind, FiscalTicket.Issuer issuer, List<TicketLine> lines) {
        return new FiscalTicket(id, kind, "TEST", id.equals("csrf-sale") ? 1 : 2, "TEST-" + id, true,
                OffsetDateTime.now(), "online", issuer, null, lines, BigDecimal.ZERO, BigDecimal.TEN,
                VatBreakdown.EMPTY, BigDecimal.ZERO, PaymentMethod.ONLINE, null, null, null,
                List.of(), "1", null, null);
    }
}
