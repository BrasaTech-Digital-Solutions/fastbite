package es.brasatech.fastbite.domain.fiscal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpanishProvincesTest {

    @Test
    void validatesPostalCodes() {
        assertThat(SpanishProvinces.isValidPostalCode("28013")).isTrue(); // Madrid
        assertThat(SpanishProvinces.isValidPostalCode("08001")).isTrue(); // Barcelona
        assertThat(SpanishProvinces.isValidPostalCode("41001")).isTrue(); // Sevilla
        assertThat(SpanishProvinces.isValidPostalCode("00000")).isFalse();
        assertThat(SpanishProvinces.isValidPostalCode("53000")).isFalse();
        assertThat(SpanishProvinces.isValidPostalCode("2801")).isFalse();
        assertThat(SpanishProvinces.isValidPostalCode("280130")).isFalse();
        assertThat(SpanishProvinces.isValidPostalCode(null)).isFalse();
    }

    @Test
    void resolvesProvinceNames() {
        assertThat(SpanishProvinces.ofPostalCode("01001")).contains("Álava");
        assertThat(SpanishProvinces.ofPostalCode("20001")).contains("Gipuzkoa");
        assertThat(SpanishProvinces.ofPostalCode("48001")).contains("Bizkaia");
        assertThat(SpanishProvinces.ofPostalCode("31001")).contains("Navarra");
        assertThat(SpanishProvinces.ofPostalCode("28013")).contains("Madrid");
        assertThat(SpanishProvinces.ofPostalCode("52001")).contains("Melilla");
    }

    @Test
    void identifiesOutsideVatTerritories() {
        assertThat(SpanishProvinces.outsideVatTerritory("35001")).isTrue(); // Las Palmas
        assertThat(SpanishProvinces.outsideVatTerritory("38001")).isTrue(); // Santa Cruz de Tenerife
        assertThat(SpanishProvinces.outsideVatTerritory("51001")).isTrue(); // Ceuta
        assertThat(SpanishProvinces.outsideVatTerritory("52001")).isTrue(); // Melilla
        assertThat(SpanishProvinces.outsideVatTerritory("28013")).isFalse(); // Madrid
        assertThat(SpanishProvinces.outsideVatTerritory(null)).isFalse();
    }

    @Test
    void identifiesForalTerritories() {
        assertThat(SpanishProvinces.isForalTerritory("01001")).isTrue(); // Álava
        assertThat(SpanishProvinces.isForalTerritory("20001")).isTrue(); // Gipuzkoa
        assertThat(SpanishProvinces.isForalTerritory("48001")).isTrue(); // Bizkaia
        assertThat(SpanishProvinces.isForalTerritory("31001")).isTrue(); // Navarra
        assertThat(SpanishProvinces.isForalTerritory("28013")).isFalse(); // Madrid
        assertThat(SpanishProvinces.isForalTerritory("35001")).isFalse(); // Las Palmas
        assertThat(SpanishProvinces.isForalTerritory(null)).isFalse();
    }
}
