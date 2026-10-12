package es.brasatech.fastbite.domain.verifactu;

import es.brasatech.fastbite.domain.fiscal.TicketKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class VerifactuTest {

    private static final LocalDate NEW_YEAR = LocalDate.of(2024, 1, 1);

    @Test
    void theFirstRecordOfAChainHasTheFingerprintOfTheAeatExample() {
        // Worked example of the AEAT specification for the fingerprint of an issued invoice
        String fingerprint = Verifactu.fingerprint("89890001K", "12345678/G33", NEW_YEAR, "F1",
                new BigDecimal("12.35"), new BigDecimal("123.45"), null,
                OffsetDateTime.parse("2024-01-01T19:20:30+01:00"));

        assertThat(fingerprint).isEqualTo("3C464DAF61ACB827C65FDA19F352A4E3BDC2C640E9E9FC4CC058073F38F12F60");
    }

    @Test
    void eachRecordIsChainedToThePreviousFingerprint() {
        String fingerprint = Verifactu.fingerprint("89890001K", "12345679/G34", NEW_YEAR, "F1",
                new BigDecimal("12.35"), new BigDecimal("123.45"),
                "3C464DAF61ACB827C65FDA19F352A4E3BDC2C640E9E9FC4CC058073F38F12F60",
                OffsetDateTime.parse("2024-01-01T19:20:35+01:00"));

        assertThat(fingerprint).isEqualTo("F7B94CFD8924EDFF273501B01EE5153E4CE8F259766F88CF6ACB8935802A2B97");
    }

    @Test
    void amountsDatesAndTimesAreWrittenTheAeatWay() {
        assertThat(Verifactu.amount(new BigDecimal("12.3"))).isEqualTo("12.30");
        assertThat(Verifactu.amount(new BigDecimal("-14.00"))).isEqualTo("-14.00");
        assertThat(Verifactu.date(LocalDate.of(2026, 10, 9))).isEqualTo("09-10-2026");
        assertThat(Verifactu.timestamp(OffsetDateTime.parse("2026-10-09T13:45:12.987+02:00")))
                .isEqualTo("2026-10-09T13:45:12+02:00");
    }

    @Test
    void theQrPointsToTheAeatCheckingPage() {
        assertThat(Verifactu.qrUrl(true, "12345678Z", "KH-T26-000001", LocalDate.of(2026, 10, 9), new BigDecimal("14.5")))
                .isEqualTo("https://www2.agenciatributaria.gob.es/wlpl/TIKE-CONT/ValidarQR"
                        + "?nif=12345678Z&numserie=KH-T26-000001&fecha=09-10-2026&importe=14.50");
        assertThat(Verifactu.qrUrl(false, "12345678Z", "A/1", LocalDate.of(2026, 10, 9), BigDecimal.ONE))
                .startsWith("https://prewww2.aeat.es/").contains("numserie=A%2F1");
    }

    @Test
    void ticketKindsMapToAeatInvoiceTypes() {
        assertThat(Verifactu.invoiceType(TicketKind.SIMPLIFIED, null)).isEqualTo("F2");
        assertThat(Verifactu.invoiceType(TicketKind.FULL, null)).isEqualTo("F1");
        assertThat(Verifactu.invoiceType(TicketKind.REPLACEMENT, null)).isEqualTo("F3");
        assertThat(Verifactu.invoiceType(TicketKind.RECTIFYING, TicketKind.SIMPLIFIED)).isEqualTo("R5");
        assertThat(Verifactu.invoiceType(TicketKind.RECTIFYING, TicketKind.FULL)).isEqualTo("R1");
        assertThat(Verifactu.invoiceType(TicketKind.RECTIFYING, TicketKind.REPLACEMENT)).isEqualTo("R1");
    }

    private static VerifactuRecord record(int sequence, String number, VerifactuRecord previous) {
        var generated = OffsetDateTime.parse("2026-10-09T13:00:00+02:00").plusSeconds(sequence);
        var link = previous == null ? null : new VerifactuRecord.Link(previous.issuerNif(), previous.invoiceNumber(),
                previous.issueDate(), previous.fingerprint());
        String fingerprint = Verifactu.fingerprint("12345678Z", number, generated.toLocalDate(), "F2",
                new BigDecimal("1.00"), new BigDecimal("11.00"), link == null ? null : link.fingerprint(), generated);
        return new VerifactuRecord("r" + sequence, "t" + sequence, VerifactuRecord.REAL_CHAIN, sequence, "F2",
                "12345678Z", "Ana", number, generated.toLocalDate(), generated, new BigDecimal("1.00"),
                new BigDecimal("11.00"), link, fingerprint, "https://qr", null, null, false,
                VerifactuRecord.Submission.PENDING);
    }

    @Test
    void aRecordIsChainedWhenItsFingerprintAndLinkMatch() {
        var first = record(1, "KH-T26-000001", null);
        var second = record(2, "KH-T26-000002", first);

        assertThat(Verifactu.chained(first, null)).isTrue();
        assertThat(Verifactu.chained(second, first)).isTrue();
    }

    @Test
    void aChangedRecordOrAWrongLinkBreaksTheChain() {
        var first = record(1, "KH-T26-000001", null);
        var second = record(2, "KH-T26-000002", first);
        var other = record(1, "KH-T26-000009", null);
        var altered = new VerifactuRecord(second.id(), second.ticketId(), second.chain(), 2, "F2", "12345678Z", "Ana",
                second.invoiceNumber(), second.issueDate(), second.generatedAt(), second.taxTotal(),
                new BigDecimal("99.00"), second.previous(), second.fingerprint(), second.qrUrl(), null, null, false,
                second.submission());

        assertThat(Verifactu.chained(altered, first)).as("amount changed after hashing").isFalse();
        assertThat(Verifactu.chained(second, other)).as("points at another record").isFalse();
        assertThat(Verifactu.chained(second, null)).as("says it follows a record that is missing").isFalse();
        assertThat(Verifactu.chained(first, second)).as("a first record that should follow one").isFalse();
    }

    @Test
    void aCancellationHasTheFingerprintOfTheAeatExample() {
        // Worked example 6.3 of the AEAT specification for the fingerprint of a registro de anulación
        String fingerprint = Verifactu.cancellationFingerprint("89890001K", "12345679/G34", NEW_YEAR,
                "F7B94CFD8924EDFF273501B01EE5153E4CE8F259766F88CF6ACB8935802A2B97",
                OffsetDateTime.parse("2024-01-01T19:20:40+01:00"));

        assertThat(fingerprint).isEqualTo("177547C0D57AC74748561D054A9CEC14B4C4EA23D1BEFD6F2E69E3A388F90C68");
    }

    @Test
    void aCancellationIsCheckedWithItsOwnFingerprint() {
        var sale = record(1, "KH-T26-000001", null);
        var link = new VerifactuRecord.Link(sale.issuerNif(), sale.invoiceNumber(), sale.issueDate(), sale.fingerprint());
        var at = sale.generatedAt().plusMinutes(3);
        var cancellation = new VerifactuRecord("c1", sale.ticketId(), sale.chain(), 2, "F2", "12345678Z", "Ana",
                sale.invoiceNumber(), sale.issueDate(), at, sale.taxTotal(), sale.amountTotal(), link,
                Verifactu.cancellationFingerprint("12345678Z", sale.invoiceNumber(), sale.issueDate(), sale.fingerprint(), at),
                sale.qrUrl(), null, null, new VerifactuRecord.Cancellation(false), false,
                VerifactuRecord.Submission.PENDING);

        assertThat(Verifactu.chained(cancellation, sale)).isTrue();
        assertThat(cancellation.amendable()).isFalse();
        assertThat(cancellation.withSubmission(new VerifactuRecord.Submission(VerifactuStatus.ACCEPTED_WITH_ERRORS, 1,
                "x", null, null)).amendable()).as("only a refused cancellation is sent again").isFalse();
        assertThat(cancellation.withSubmission(new VerifactuRecord.Submission(VerifactuStatus.REJECTED, 1, "x", null,
                null)).amendable()).isTrue();
    }
}
