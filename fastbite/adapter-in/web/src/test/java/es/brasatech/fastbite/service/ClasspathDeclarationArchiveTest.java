package es.brasatech.fastbite.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Reads src/test/resources/declarations: 9.9.9 is signed, 9.9.8's PDF doesn't match its recorded hash. */
class ClasspathDeclarationArchiveTest {

    private final ClasspathDeclarationArchive archive = new ClasspathDeclarationArchive();

    @Test
    void aSignedVersionIsFoundWithItsDetailsAndPdf() {
        var signed = archive.forVersion("9.9.9");

        assertThat(signed).isPresent();
        assertThat(signed.get().date()).isEqualTo("3 de noviembre de 2026");
        assertThat(signed.get().signatory()).isEqualTo("Ana García, administradora única");
        assertThat(archive.pdf("9.9.9")).isPresent();
    }

    @Test
    void aPdfThatDoesNotMatchItsRecordedHashIsIgnored() {
        assertThat(archive.forVersion("9.9.8")).isEmpty();
        assertThat(archive.pdf("9.9.8")).isEmpty();
        assertThat(archive.all()).extracting(signed -> signed.version()).containsExactly("9.9.9");
    }
}
