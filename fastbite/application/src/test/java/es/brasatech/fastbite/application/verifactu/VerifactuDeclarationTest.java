package es.brasatech.fastbite.application.verifactu;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VerifactuDeclarationTest {

    private static VerifactuSystem system(String nif) {
        return new VerifactuSystem("Brasatech Digital SL", nif, "FastBite", "FB", "1.0", "Servicios de restauración");
    }

    @Test
    void itStaysADraftWhileAnyDetailIsMissingEvenIfMarkedSigned() {
        var declaration = new VerifactuDeclaration(system(""), "", "Madrid", "", "", true);

        assertThat(declaration.signed()).isFalse();
        assertThat(declaration.missing()).containsExactly("fastbite.verifactu.producer-nif",
                "fastbite.verifactu.producer-address", "fastbite.verifactu.declaration-date",
                "fastbite.verifactu.declaration-signatory");
    }

    @Test
    void itIsSignedOnlyWhenCompleteAndMarkedSigned() {
        var complete = new VerifactuDeclaration(system("B12345674"), "Calle Mayor 1, 28013 Madrid", "Madrid",
                "15 de noviembre de 2026", "Ana García, administradora única", true);
        var notYetSigned = new VerifactuDeclaration(system("B12345674"), "Calle Mayor 1, 28013 Madrid", "Madrid",
                "15 de noviembre de 2026", "Ana García, administradora única", false);

        assertThat(complete.signed()).isTrue();
        assertThat(notYetSigned.signed()).isFalse();
        assertThat(complete.onlyVerifactu()).isTrue();
        assertThat(complete.multipleTaxpayers()).isTrue();
    }
}
