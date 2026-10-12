package es.brasatech.fastbite.verifactu.aeat;

import es.brasatech.fastbite.application.verifactu.VerifactuSubmitter;
import es.brasatech.fastbite.application.verifactu.VerifactuSystem;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.SpanishTaxId;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.verifactu.VerifactuStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sends simplified invoices to the AEAT's test environment, with the producer's own NIF as the
 * issuer, so no representation is needed. Never runs in a normal build:
 *
 * <pre>
 * VERIFACTU_CERTIFICATE_PATH=... VERIFACTU_CERTIFICATE_PASSWORD=... VERIFACTU_LIVE_NIF=B... VERIFACTU_LIVE_NAME="..." \
 *   ./mvnw -pl fastbite/adapter-out/verifactu-aeat test -Dtest=AeatLiveTest -Dverifactu.live=true
 * </pre>
 *
 * The NIF and name must be the certificate holder's, exactly as the AEAT knows them.
 */
@EnabledIfSystemProperty(named = "verifactu.live", matches = "true")
class AeatLiveTest {

    @Test
    void theAeatTestEnvironmentAnswersForOneRecord() {
        String nif = env("VERIFACTU_LIVE_NIF");
        String name = env("VERIFACTU_LIVE_NAME");
        assertThat(SpanishTaxId.parse(nif).map(SpanishTaxId::value))
                .as("VERIFACTU_LIVE_NIF \"%s\" must be a valid NIF, written as nine characters without spaces", nif)
                .contains(nif);
        VerifactuSystem system = new VerifactuSystem(name, nif, "FastBite", "FB", "1.0", "Servicios de restauración");
        var submitter = new AeatVerifactuSubmitter(env("VERIFACTU_CERTIFICATE_PATH"),
                env("VERIFACTU_CERTIFICATE_PASSWORD"), false, false, "", system);
        assertThat(submitter.configured()).as("certificate loads").isTrue();

        // A new series and installation each run, so the AEAT sees a new invoice in a new chain
        String series = "FBTEST" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmss"));
        var installation = new VerifactuSubmitter.Installation("live-" + series, false);
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Europe/Madrid")).truncatedTo(ChronoUnit.SECONDS);
        FiscalTicket ticket = AeatFixtures.ticket(now,
                new FiscalTicket.Issuer(name, null, nif, "Calle de prueba 1", "28013", "Madrid", "Madrid", null),
                TicketKind.SIMPLIFIED, series + "-000001", null, null, "12.10", "10");
        VerifactuSubmitter.Submission submission = AeatFixtures.submission(ticket, "F2", null, now);

        List<VerifactuSubmitter.Result> results = submitter.submit(system, installation, List.of(submission), false);

        System.out.println("AEAT test environment answered: " + results);
        assertThat(results).singleElement().satisfies(result ->
                assertThat(result.recordId()).isEqualTo(submission.record().id()));
    }

    /**
     * Sends a record under a wrong issuer name, then amends it with the right one (subsanación),
     * flagged as the AEAT's first answer requires. Waits a minute between the two, as the AEAT asks.
     */
    @Test
    void theAeatTestEnvironmentTakesASubsanacion() throws InterruptedException {
        String nif = env("VERIFACTU_LIVE_NIF");
        String name = env("VERIFACTU_LIVE_NAME");
        VerifactuSystem system = new VerifactuSystem(name, nif, "FastBite", "FB", "1.0", "Servicios de restauración");
        var submitter = new AeatVerifactuSubmitter(env("VERIFACTU_CERTIFICATE_PATH"),
                env("VERIFACTU_CERTIFICATE_PASSWORD"), false, false, "", system);

        String series = "FBSUB" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmss"));
        var installation = new VerifactuSubmitter.Installation("live-" + series, false);
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Europe/Madrid")).truncatedTo(ChronoUnit.SECONDS);
        FiscalTicket ticket = AeatFixtures.ticket(now,
                new FiscalTicket.Issuer("NOMBRE EQUIVOCADO DE PRUEBA SL", null, nif, "Calle de prueba 1", "28013",
                        "Madrid", "Madrid", null),
                TicketKind.SIMPLIFIED, series + "-000001", null, null, "12.10", "10");
        VerifactuSubmitter.Submission wrong = AeatFixtures.submission(ticket, "F2", null, now);
        var first = submitter.submit(system, installation, List.of(wrong), false);
        System.out.println("AEAT answered the record with the wrong name: " + first);
        // The test environment does not check the name, so this may well be accepted: a wrong detail
        // found later is amended the same way (Subsanacion without RechazoPrevio)
        assertThat(first).singleElement();

        Thread.sleep(61_000);
        boolean afterRejection = first.getFirst().status() == VerifactuStatus.REJECTED;
        VerifactuSubmitter.Submission amendment = AeatFixtures.amendment(wrong, afterRejection, name,
                OffsetDateTime.now(ZoneId.of("Europe/Madrid")).truncatedTo(ChronoUnit.SECONDS));
        var second = submitter.submit(system, installation, List.of(amendment), false);

        System.out.println("AEAT answered the subsanación (RechazoPrevio " + (afterRejection ? "X" : "N") + "): " + second);
        assertThat(second).singleElement().satisfies(result ->
                assertThat(result.status()).isIn(VerifactuStatus.ACCEPTED, VerifactuStatus.ACCEPTED_WITH_ERRORS));
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + name + " to run the live test");
        }
        return value;
    }
}
