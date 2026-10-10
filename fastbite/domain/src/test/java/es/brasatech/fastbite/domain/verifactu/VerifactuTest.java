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
}
