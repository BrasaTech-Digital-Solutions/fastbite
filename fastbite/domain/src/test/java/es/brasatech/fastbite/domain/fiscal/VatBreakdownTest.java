package es.brasatech.fastbite.domain.fiscal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VatBreakdownTest {

    private static VatBreakdown.Amount amount(String gross, String rate) {
        return new VatBreakdown.Amount(new BigDecimal(gross), new BigDecimal(rate));
    }

    @Test
    void takesTheVatOutOfAVatIncludedPrice() {
        var breakdown = VatBreakdown.of(List.of(amount("11.00", "10")));

        assertThat(breakdown.gross()).isEqualByComparingTo("11.00");
        assertThat(breakdown.base()).isEqualByComparingTo("10.00");
        assertThat(breakdown.vat()).isEqualByComparingTo("1.00");
        assertThat(breakdown.mixedRates()).isFalse();
    }

    @Test
    void showsOneLinePerRateHighestFirst() {
        var breakdown = VatBreakdown.of(List.of(amount("11.00", "10"), amount("6.05", "21"), amount("2.20", "10")));

        assertThat(breakdown.lines()).hasSize(2);
        var general = breakdown.lines().get(0);
        assertThat(general.rate()).isEqualByComparingTo("21");
        assertThat(general.base()).isEqualByComparingTo("5.00");
        assertThat(general.vat()).isEqualByComparingTo("1.05");
        var reduced = breakdown.lines().get(1);
        assertThat(reduced.gross()).isEqualByComparingTo("13.20");
        assertThat(reduced.base()).isEqualByComparingTo("12.00");
        assertThat(reduced.vat()).isEqualByComparingTo("1.20");
        assertThat(breakdown.gross()).isEqualByComparingTo("19.25");
    }

    @Test
    void baseAndVatAlwaysAddUpToWhatTheGuestPays() {
        var breakdown = VatBreakdown.of(List.of(amount("3.33", "10"), amount("7.77", "21"), amount("0.99", "4")));

        for (var line : breakdown.lines()) {
            assertThat(line.base().add(line.vat())).isEqualByComparingTo(line.gross());
        }
        assertThat(breakdown.base().add(breakdown.vat())).isEqualByComparingTo(breakdown.gross());
        assertThat(breakdown.gross()).isEqualByComparingTo("12.09");
    }

    @Test
    void sharesADiscountOverTheRatesToTheCent() {
        var breakdown = VatBreakdown.of(List.of(amount("10.00", "10"), amount("5.00", "21")), new BigDecimal("1.00"));

        assertThat(breakdown.gross()).isEqualByComparingTo("14.00");
        assertThat(breakdown.lines().get(0).gross()).isEqualByComparingTo("4.67"); // 21%: 5.00 - 0.33
        assertThat(breakdown.lines().get(1).gross()).isEqualByComparingTo("9.33"); // 10%: 10.00 - 0.67
    }

    @Test
    void neverDiscountsBelowZero() {
        var breakdown = VatBreakdown.of(List.of(amount("4.00", "10")), new BigDecimal("9.00"));

        assertThat(breakdown.gross()).isEqualByComparingTo("0.00");
        assertThat(breakdown.vat()).isEqualByComparingTo("0.00");
    }

    @Test
    void anUnknownRateCountsAsTheRestaurantRate() {
        var breakdown = VatBreakdown.of(List.of(new VatBreakdown.Amount(new BigDecimal("11.00"), null)));

        assertThat(breakdown.lines().getFirst().rate()).isEqualByComparingTo(VatRates.DEFAULT);
    }

    @Test
    void refundsAreBrokenDownAsNegativeAmounts() {
        var breakdown = VatBreakdown.of(List.of(amount("-11.00", "10")));

        assertThat(breakdown.base()).isEqualByComparingTo("-10.00");
        assertThat(breakdown.vat()).isEqualByComparingTo("-1.00");
    }

    @Test
    void nothingToBreakDownGivesZeroes() {
        assertThat(VatBreakdown.of(List.of())).isEqualTo(VatBreakdown.EMPTY);
        assertThat(VatBreakdown.EMPTY.gross()).isEqualByComparingTo("0");
    }
}
