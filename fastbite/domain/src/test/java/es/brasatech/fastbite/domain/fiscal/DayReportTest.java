package es.brasatech.fastbite.domain.fiscal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DayReportTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 13);

    static int minute;

    static FiscalTicket ticket(String id, TicketKind kind, String series, int number, PaymentMethod method,
            String total, String tip, String rate, String corrects) {
        BigDecimal gross = new BigDecimal(total);
        return new FiscalTicket(id, kind, series, number, series + "-" + String.format("%06d", number), false,
                OffsetDateTime.of(DAY.atTime(12, minute++), ZoneOffset.ofHours(2)), "carla", null, null, List.of(),
                BigDecimal.ZERO, gross, VatBreakdown.of(List.of(new VatBreakdown.Amount(gross, new BigDecimal(rate)))),
                new BigDecimal(tip), method, null, null,
                corrects == null ? null : new FiscalTicket.Correction(corrects, "KH-T26-000001", DAY, "wrong dish"),
                List.of(), "12", null, null);
    }

    static FiscalTicket sale(String id, int number, PaymentMethod method, String total, String rate) {
        return ticket(id, TicketKind.SIMPLIFIED, "KH-T26", number, method, total, "0.00", rate, null);
    }

    @Test
    void salesAndRefundsCountByHowTheyWerePaid() {
        var report = DayReport.of(DAY, List.of(
                sale("a", 1, PaymentMethod.CASH, "11.00", "10"),
                sale("b", 2, PaymentMethod.CARD, "12.10", "21"),
                ticket("c", TicketKind.SIMPLIFIED, "KH-T26", 3, PaymentMethod.CASH, "5.50", "0.50", "10", null),
                ticket("r", TicketKind.RECTIFYING, "KH-R26", 1, PaymentMethod.CASH, "-11.00", "0.00", "10", "a")),
                List.of());

        assertThat(report.salesCount()).isEqualTo(3);
        assertThat(report.cashSales()).isEqualByComparingTo("17.00"); // 11.00 + 5.50 + 0.50 tip
        assertThat(report.cashRefunds()).isEqualByComparingTo("-11.00");
        assertThat(report.cashNet()).isEqualByComparingTo("6.00");
        assertThat(report.methods()).extracting(DayReport.MethodTotal::method)
                .containsExactly(PaymentMethod.CASH, PaymentMethod.CARD);
        assertThat(report.net()).isEqualByComparingTo("17.60"); // 11.00 + 12.10 + 5.50 - 11.00
        assertThat(report.tips()).isEqualByComparingTo("0.50");
        assertThat(report.takings()).isEqualByComparingTo("18.10");
        assertThat(report.refunds()).extracting(FiscalTicket::id).containsExactly("r");
    }

    @Test
    void vatAddsUpRateByRateHighestFirst() {
        var report = DayReport.of(DAY, List.of(
                sale("a", 1, PaymentMethod.CASH, "11.00", "10"),
                sale("b", 2, PaymentMethod.CARD, "12.10", "21"),
                sale("c", 3, PaymentMethod.CARD, "22.00", "10")), List.of());

        assertThat(report.vat()).extracting(VatBreakdown.Line::rate).map(BigDecimal::intValue).containsExactly(21, 10);
        assertThat(report.vat().get(0).base()).isEqualByComparingTo("10.00");
        assertThat(report.vat().get(0).vat()).isEqualByComparingTo("2.10");
        assertThat(report.vat().get(1).base()).isEqualByComparingTo("30.00");
        assertThat(report.vat().get(1).vat()).isEqualByComparingTo("3.00");
        assertThat(report.vatBase()).isEqualByComparingTo("40.00");
        assertThat(report.vatAmount()).isEqualByComparingTo("5.10");
    }

    @Test
    void aCancelledTicketAndAReplacementCountNothingButAreListed() {
        var cancelled = sale("x", 2, PaymentMethod.CASH, "30.00", "10");
        var report = DayReport.of(DAY, List.of(
                sale("a", 1, PaymentMethod.CASH, "11.00", "10"),
                cancelled,
                ticket("f", TicketKind.REPLACEMENT, "KH-F26", 1, PaymentMethod.CASH, "11.00", "0.00", "10", "a")),
                List.of(new TicketCancellation("x", cancelled.issuedAt().plusMinutes(1), "carla", "wrong table")));

        assertThat(report.cashNet()).isEqualByComparingTo("11.00");
        assertThat(report.salesCount()).isEqualTo(1);
        assertThat(report.vatBase()).isEqualByComparingTo("10.00");
        assertThat(report.cancelled()).extracting(c -> c.ticket().id()).containsExactly("x");
        assertThat(report.replacements()).extracting(FiscalTicket::id).containsExactly("f");
        assertThat(report.series()).containsExactly(
                new DayReport.SeriesRange("KH-F26", "KH-F26-000001", "KH-F26-000001", 1),
                new DayReport.SeriesRange("KH-T26", "KH-T26-000001", "KH-T26-000002", 2));
    }

    @Test
    void aDayWithoutTicketsIsEmptyAndHasNoCash() {
        var report = DayReport.of(DAY, List.of(), List.of());

        assertThat(report.empty()).isTrue();
        assertThat(report.cashNet()).isEqualByComparingTo("0");
        assertThat(report.methods()).isEmpty();
        assertThat(report.vat()).isEmpty();
    }
}
