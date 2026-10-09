package es.brasatech.fastbite.domain.allergen;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AllergenTest {

    @Test
    void thereAreTheFourteenAllergensOfTheRegulation() {
        assertThat(Allergen.values()).hasSize(14);
        assertThat(Allergen.values()[0]).isEqualTo(Allergen.GLUTEN);
        assertThat(Allergen.values()[13]).isEqualTo(Allergen.MOLLUSCS);
        assertThat(Allergen.MILK.messageKey()).isEqualTo("allergen.milk");
    }

    @Test
    void notDeclaredAndDeclaredAsNoneAreDifferent() {
        assertThat(Allergen.parse(null)).isNull();
        assertThat(Allergen.parse("")).isEmpty();
        assertThat(Allergen.format(null)).isNull();
        assertThat(Allergen.format(Set.of())).isEmpty();
    }

    @Test
    void storesInTheOrderOfTheRegulation() {
        assertThat(Allergen.format(List.of(Allergen.MILK, Allergen.GLUTEN, Allergen.SESAME)))
                .isEqualTo("GLUTEN,MILK,SESAME");
        assertThat(Allergen.parse("milk, gluten,UNKNOWN,,sesame"))
                .containsExactly(Allergen.GLUTEN, Allergen.MILK, Allergen.SESAME);
    }
}
