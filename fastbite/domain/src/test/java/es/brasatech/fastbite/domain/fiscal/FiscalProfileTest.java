package es.brasatech.fastbite.domain.fiscal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FiscalProfileTest {

    private static FiscalProfile complete() {
        return new FiscalProfile("Ana García López", "Kebab Ana", "12345678z", "Calle Mayor 1", "28013", "Madrid",
                "Madrid", "+34 600 000 000", "ana@example.com", "ka", new BigDecimal("10"));
    }

    @Test
    void aProfileWithEveryRequiredFieldCanIssueTickets() {
        var profile = complete();

        assertThat(profile.complete()).isTrue();
        assertThat(profile.problems()).isEmpty();
        assertThat(profile.taxId()).isEqualTo("12345678Z");
        assertThat(profile.seriesPrefix()).isEqualTo("KA");
        assertThat(profile.taxIdKind()).isEqualTo(SpanishTaxId.Kind.DNI);
        assertThat(profile.displayName()).isEqualTo("Kebab Ana");
    }

    @Test
    void anEmptyProfileListsEveryRequiredField() {
        assertThat(FiscalProfile.EMPTY.complete()).isFalse();
        assertThat(FiscalProfile.EMPTY.missingFields())
                .containsExactly("legalName", "taxId", "addressLine", "postalCode", "city", "province", "seriesPrefix");
        assertThat(FiscalProfile.EMPTY.defaultVatRate()).isEqualByComparingTo("10");
    }

    @Test
    void explainsWrongValuesFieldByField() {
        var profile = new FiscalProfile("  Ana  ", null, "12345678A", "Calle Mayor 1", "99999", "Madrid", "Madrid",
                "call me", "not-an-email", "K-1", new BigDecimal("10"));

        assertThat(profile.legalName()).isEqualTo("Ana");
        assertThat(profile.problems())
                .containsEntry("taxId", "fiscal.error.taxId")
                .containsEntry("postalCode", "fiscal.error.postalCode")
                .containsEntry("phone", "fiscal.error.phone")
                .containsEntry("email", "fiscal.error.email")
                .containsEntry("seriesPrefix", "fiscal.error.seriesPrefix");
    }

    @Test
    void refusesPlacesOutsideSpanishVat() {
        var canaries = new FiscalProfile("Ana", null, "12345678Z", "Calle Mayor 1", "35001", "Las Palmas",
                "Las Palmas", null, null, "K", null);

        assertThat(canaries.problems()).containsEntry("postalCode", "fiscal.error.postalCode.territory");
    }

    @Test
    void refusesForalTerritories() {
        for (String pc : java.util.List.of("01001", "20001", "48001", "31001")) {
            var foral = new FiscalProfile("Ana", null, "12345678Z", "Calle Mayor 1", pc, "Town",
                    "Province", null, null, "K", null);
            assertThat(foral.problems()).containsEntry("postalCode", "fiscal.error.postalCode.foral");
        }
    }

    @Test
    void anUnknownVatRateFallsBackToTheRestaurantRate() {
        var profile = new FiscalProfile(null, null, null, null, null, null, null, null, null, null, new BigDecimal("7"));

        assertThat(profile.defaultVatRate()).isEqualByComparingTo(VatRates.REDUCED);
    }

    @Test
    void suggestsAShortPrefixFromTheBusinessName() {
        assertThat(FiscalProfile.suggestPrefix("Kebab House Centro")).isEqualTo("KHC");
        assertThat(FiscalProfile.suggestPrefix("Cafetería")).isEqualTo("CAF");
        assertThat(FiscalProfile.suggestPrefix("Ñam Ñam")).isEqualTo("NN");
        assertThat(FiscalProfile.suggestPrefix("  ")).isEqualTo("T");
    }

    @Test
    void knowsTheProvinceOfAPostalCode() {
        assertThat(SpanishProvinces.ofPostalCode("28013")).hasValue("Madrid");
        assertThat(SpanishProvinces.ofPostalCode("08001")).hasValue("Barcelona");
        assertThat(SpanishProvinces.ofPostalCode("01001")).hasValue("Álava");
        assertThat(SpanishProvinces.ofPostalCode("53000")).isEmpty();
        assertThat(SpanishProvinces.ofPostalCode("2801")).isEmpty();
    }
}
