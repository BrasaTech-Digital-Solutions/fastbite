package es.brasatech.fastbite.application.verifactu;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VerifactuDeclarationTest {

    private static final DeclarationArchive.SignedDeclaration V1 = new DeclarationArchive.SignedDeclaration("1.0.0",
            "3 de noviembre de 2026", "Santa Pola (Alicante), España", "Ana García, administradora única", "ANA GARCIA",
            "2026-11-03T10:00:00Z", "abc");
    private static final DeclarationArchive.SignedDeclaration V2 = new DeclarationArchive.SignedDeclaration("1.1.0",
            "1 de diciembre de 2026", "Santa Pola (Alicante), España", "Ana García, administradora única", "ANA GARCIA",
            "2026-12-01T10:00:00Z", "def");

    private static VerifactuSystem system(String nif, String version) {
        return new VerifactuSystem("Brasatech Digital SL", nif, "FastBite", "FB", version, "Servicios de restauración");
    }

    private static DeclarationArchive archive(DeclarationArchive.SignedDeclaration... signed) {
        return new DeclarationArchive() {
            @Override public Optional<SignedDeclaration> forVersion(String version) {
                return List.of(signed).stream().filter(s -> s.version().equals(version)).findFirst();
            }
            @Override public List<SignedDeclaration> all() { return List.of(signed).reversed(); }
        };
    }

    @Test
    void itStaysADraftWhileAnyDetailIsMissingEvenIfSigned() {
        var declaration = new VerifactuDeclaration(system("", "1.0.0"), "", "Madrid", "", archive(V1));

        assertThat(declaration.signed()).isFalse();
        assertThat(declaration.missing()).containsExactly("fastbite.verifactu.producer-nif",
                "fastbite.verifactu.producer-address", "fastbite.verifactu.declaration-signatory");
    }

    @Test
    void itIsSignedOnlyWhenTheRunningVersionHasItsOwnSignedDeclaration() {
        var signed = new VerifactuDeclaration(system("B12345674", "1.1.0"), "Calle Mayor 1, 28013 Madrid", "Madrid",
                "Ana García, administradora única", archive(V1, V2));
        var newerThanItsDeclaration = new VerifactuDeclaration(system("B12345674", "1.2.0"),
                "Calle Mayor 1, 28013 Madrid", "Madrid", "Ana García, administradora única", archive(V1, V2));

        assertThat(signed.signed()).isTrue();
        assertThat(signed.date()).isEqualTo("1 de diciembre de 2026");
        assertThat(signed.previous()).containsExactly(V1);
        assertThat(newerThanItsDeclaration.signed()).isFalse();
        assertThat(newerThanItsDeclaration.date()).isEmpty();
        assertThat(newerThanItsDeclaration.previous()).containsExactly(V2, V1);
        assertThat(signed.onlyVerifactu()).isTrue();
        assertThat(signed.multipleTaxpayers()).isTrue();
    }
}
