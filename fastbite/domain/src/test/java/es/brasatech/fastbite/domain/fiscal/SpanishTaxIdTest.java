package es.brasatech.fastbite.domain.fiscal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpanishTaxIdTest {

    @Test
    void acceptsADniWithTheRightLetter() {
        assertThat(SpanishTaxId.parse("12345678Z")).hasValueSatisfying(id -> {
            assertThat(id.value()).isEqualTo("12345678Z");
            assertThat(id.kind()).isEqualTo(SpanishTaxId.Kind.DNI);
            assertThat(id.kind().naturalPerson()).isTrue();
        });
    }

    @Test
    void readsNumbersTheWayPeopleTypeThem() {
        assertThat(SpanishTaxId.parse(" 12.345.678-z ")).map(SpanishTaxId::value).hasValue("12345678Z");
        assertThat(SpanishTaxId.parse("ES12345678Z")).map(SpanishTaxId::value).hasValue("12345678Z");
    }

    @Test
    void rejectsADniWithTheWrongLetter() {
        assertThat(SpanishTaxId.parse("12345678A")).isEmpty();
    }

    @Test
    void acceptsNiesOfForeignResidents() {
        assertThat(SpanishTaxId.parse("X1234567L")).map(SpanishTaxId::kind).hasValue(SpanishTaxId.Kind.NIE);
        assertThat(SpanishTaxId.parse("Y1234567X")).map(SpanishTaxId::kind).hasValue(SpanishTaxId.Kind.NIE);
        assertThat(SpanishTaxId.parse("Z1234567R")).map(SpanishTaxId::kind).hasValue(SpanishTaxId.Kind.NIE);
        assertThat(SpanishTaxId.parse("X1234567A")).isEmpty();
    }

    @Test
    void acceptsCompanyNumbersWithTheirControlCharacter() {
        // A: limited company, digit control
        assertThat(SpanishTaxId.parse("A58818501")).map(SpanishTaxId::kind).hasValue(SpanishTaxId.Kind.COMPANY);
        // B: limited liability company
        assertThat(SpanishTaxId.parse("B12345674")).isPresent();
        // Q: public body, letter control
        assertThat(SpanishTaxId.parse("Q2826000H")).isPresent();
    }

    @Test
    void rejectsCompanyNumbersWithTheWrongControlOrKindOfControl() {
        assertThat(SpanishTaxId.parse("A58818502")).isEmpty();
        // An A company never ends in a letter, a Q body never in a digit
        assertThat(SpanishTaxId.parse("A5881850A")).isEmpty();
        assertThat(SpanishTaxId.parse("Q28260008")).isEmpty();
    }

    @Test
    void rejectsAnythingElse() {
        assertThat(SpanishTaxId.parse(null)).isEmpty();
        assertThat(SpanishTaxId.parse("")).isEmpty();
        assertThat(SpanishTaxId.parse("1234")).isEmpty();
        assertThat(SpanishTaxId.parse("FR12345678901")).isEmpty();
    }
}
