package es.brasatech.fastbite.verifactu.aeat;

import com.sun.net.httpserver.HttpServer;
import es.brasatech.fastbite.application.verifactu.VerifactuSubmitter;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.verifactu.VerifactuStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static es.brasatech.fastbite.verifactu.aeat.AeatFixtures.SYSTEM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AeatVerifactuSubmitterTest {

    /** A clock the test moves forward. */
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-09T11:00:00Z");
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    HttpServer server;
    final List<String> requests = new ArrayList<>();
    final List<String> paths = new ArrayList<>();
    String answer;
    int status = 200;
    final TestClock clock = new TestClock();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            paths.add(exchange.getRequestURI().getPath());
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/xml; charset=UTF-8");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    AeatVerifactuSubmitter submitter(boolean productionEnabled, Set<String> issuers) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new AeatVerifactuSubmitter(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                base + "/test", base + "/production", productionEnabled, issuers, clock);
    }

    @Test
    void sendsTheRecordsAndKeepsEachAnswer() {
        var chain = AeatFixtures.chain().subList(0, 3);
        answer = AeatResponseTest.answer("ParcialmenteCorrecto", "60",
                AeatResponseTest.line("KH-T26-000001", "Correcto", null, null),
                AeatResponseTest.line("KH-F26-000001", "AceptadoConErrores", "2000", "NIF del destinatario no identificado"),
                AeatResponseTest.line("KH-F26-000002", "Incorrecto", "1100", "Valor o tipo incorrecto del campo"));

        List<VerifactuSubmitter.Result> results = submitter(false, Set.of()).submit(SYSTEM, AeatFixtures.INSTALLATION, chain, false);

        assertThat(paths).containsExactly("/test");
        assertThat(requests.getFirst()).contains("RegFactuSistemaFacturacion", "KH-T26-000001", "Envelope");
        assertThat(results).extracting(VerifactuSubmitter.Result::status).containsExactly(VerifactuStatus.ACCEPTED,
                VerifactuStatus.ACCEPTED_WITH_ERRORS, VerifactuStatus.REJECTED);
        assertThat(results.get(0).recordId()).isEqualTo(chain.get(0).record().id());
        assertThat(results.get(0).csv()).isEqualTo("A-TEST-CSV");
        assertThat(results.get(1).error()).isEqualTo("2000: NIF del destinatario no identificado");
        assertThat(results.get(2).csv()).isNull();
    }

    @Test
    void waitsAsLongAsTheAeatAsksBeforeSendingAgain() {
        var chain = AeatFixtures.chain();
        answer = AeatResponseTest.answer("Correcto", "60", AeatResponseTest.line("KH-T26-000001", "Correcto", null, null));
        var submitter = submitter(false, Set.of());

        submitter.submit(SYSTEM, AeatFixtures.INSTALLATION, chain.subList(0, 1), false);
        assertThat(submitter.submit(SYSTEM, AeatFixtures.INSTALLATION, chain.subList(1, 2), false)).as("too soon").isEmpty();
        clock.now = clock.now.plusSeconds(61);
        submitter.submit(SYSTEM, AeatFixtures.INSTALLATION, chain.subList(1, 2), false);

        assertThat(requests).hasSize(2);
    }

    @Test
    void productionStaysClosedUntilEnabled() {
        answer = AeatResponseTest.answer("Correcto", "60", AeatResponseTest.line("KH-T26-000001", "Correcto", null, null));

        assertThat(submitter(false, Set.of()).submit(SYSTEM, AeatFixtures.INSTALLATION, AeatFixtures.chain().subList(0, 1), true)).isEmpty();
        assertThat(requests).isEmpty();

        submitter(true, Set.of()).submit(SYSTEM, AeatFixtures.INSTALLATION, AeatFixtures.chain().subList(0, 1), true);
        assertThat(paths).containsExactly("/production");
    }

    @Test
    void onlyTheListedIssuersAreSentAndTheRestWait() {
        answer = AeatResponseTest.answer("Correcto", "60");

        assertThat(submitter(false, Set.of("12345678Z")).submit(SYSTEM, AeatFixtures.INSTALLATION, AeatFixtures.chain(), false)).isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    void recordsWithoutAValidTaxIdAreRefusedWithoutBeingSent() {
        // A sandbox location without business details: the service records an empty NIF
        var withoutNif = AeatFixtures.submission(AeatFixtures.ticket(
                new FiscalTicket.Issuer("Demo", null, "", null, null, null, null, null),
                TicketKind.SIMPLIFIED, "TEST-KH-T26-000001", null, null, "5.00", "10"), "F2", null);

        var results = submitter(false, Set.of()).submit(SYSTEM, AeatFixtures.INSTALLATION, List.of(withoutNif), false);

        assertThat(requests).isEmpty();
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(VerifactuStatus.REJECTED);
            assertThat(result.error()).contains("NIF");
        });
    }

    @Test
    void aRefusedSubmissionLeavesTheRecordsPending() {
        status = 500;
        answer = """
                <env:Envelope xmlns:env="http://schemas.xmlsoap.org/soap/envelope/"><env:Body><env:Fault>
                <faultcode>env:Client</faultcode><faultstring>Codigo[4102].El XML no cumple el esquema</faultstring>
                </env:Fault></env:Body></env:Envelope>""";
        var submitter = submitter(false, Set.of());

        assertThatThrownBy(() -> submitter.submit(SYSTEM, AeatFixtures.INSTALLATION, AeatFixtures.chain().subList(0, 1), false))
                .hasMessageContaining("4102").hasMessageContaining("HTTP 500");
        assertThat(submitter.submit(SYSTEM, AeatFixtures.INSTALLATION, AeatFixtures.chain().subList(0, 1), false)).as("not resent at once").isEmpty();
    }
}
