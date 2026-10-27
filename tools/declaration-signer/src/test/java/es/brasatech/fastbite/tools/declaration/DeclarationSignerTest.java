package es.brasatech.fastbite.tools.declaration;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclarationSignerTest {

    private static final Path REPO = Path.of("../..").toAbsolutePath().normalize();
    private static final char[] PASSWORD = "test-password".toCharArray();

    @TempDir
    Path folder;

    @Test
    void theDeclarationIsTheAppsTemplateCompleteForTheVersionAndTheDay() throws Exception {
        DeclarationDetails details = DeclarationDetails.fromRepository(REPO, "1.2.0", LocalDate.of(2026, 11, 3));

        String html = Files.readString(DeclarationRenderer.render(REPO, details, folder));

        assertTrue(html.contains("DECLARACIÓN RESPONSABLE DEL SISTEMA INFORMÁTICO DE FACTURACIÓN"));
        assertTrue(html.contains("<dd>1.2.0</dd>"), "the version");
        assertTrue(html.contains("B09767898"));
        assertTrue(html.contains("3 de noviembre de 2026"));
        assertTrue(html.contains("Santa Pola (Alicante), España"));
        assertTrue(html.contains("p.p. Ricardo Maximino Gonçalves de Moraes, administrador único"));
        assertTrue(html.contains("href=\"css/legal.css\""));
        assertTrue(Files.exists(folder.resolve("css/legal.css")));
        assertFalse(html.contains("Borrador"));
    }

    @Test
    void onlyReleaseVersionsAreDeclared() {
        assertThrows(IllegalArgumentException.class,
                () -> DeclarationDetails.fromRepository(REPO, "0.0.1-SNAPSHOT", LocalDate.now()));
    }

    @Test
    void aSignedPdfVerifiesAndAnyChangeBreaksIt() throws Exception {
        Path certificate = selfSignedPkcs12(folder.resolve("test.p12"));
        Path unsigned = folder.resolve("unsigned.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(unsigned.toFile());
        }
        Path signed = folder.resolve("signed.pdf");

        PadesSigner signer = PadesSigner.fromPkcs12(certificate, PASSWORD);
        signer.sign(unsigned, signed, "Declaración responsable de FastBite 1.2.0", "Santa Pola (Alicante), España");

        var checks = PadesSigner.verify(signed);
        assertEquals(1, checks.size());
        assertTrue(checks.getFirst().ok(), checks.toString());
        assertEquals("BRASATECH TEST", checks.getFirst().signer());

        byte[] bytes = Files.readAllBytes(signed);
        Path appended = folder.resolve("appended.pdf");
        try (OutputStream out = Files.newOutputStream(appended)) {
            out.write(bytes);
            out.write("\n% changed after signing\n".getBytes());
        }
        assertFalse(PadesSigner.verify(appended).getFirst().ok(), "bytes after the signature are not covered");
    }

    private static Path selfSignedPkcs12(Path file) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        X500Name name = new X500Name("CN=BRASATECH TEST, O=Test");
        Instant now = Instant.now();
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now.toEpochMilli()), Date.from(now.minus(1, ChronoUnit.DAYS)),
                Date.from(now.plus(1, ChronoUnit.DAYS)), name, pair.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("signer", pair.getPrivate(), PASSWORD, new Certificate[]{certificate});
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, PASSWORD);
        }
        return file;
    }
}
