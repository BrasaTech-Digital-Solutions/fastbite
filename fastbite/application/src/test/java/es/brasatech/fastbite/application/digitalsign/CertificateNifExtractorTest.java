package es.brasatech.fastbite.application.digitalsign;

import es.brasatech.fastbite.application.digitalsign.CertificateNifExtractor.Status;
import es.brasatech.fastbite.application.digitalsign.CertificateNifExtractor.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CertificateNifExtractorTest {

    @Test
    void matchesPlainNifNieAndCif() {
        VerificationResult dni = CertificateNifExtractor.verify(Map.of("nif", "12345678z"), "12345678Z");
        assertThat(dni.status()).isEqualTo(Status.MATCH);
        assertThat(dni.matchedNif()).isEqualTo("12345678Z");

        VerificationResult nie = CertificateNifExtractor.verify(Map.of("serialNumber", "X-1234567-L"), "X1234567L");
        assertThat(nie.status()).isEqualTo(Status.MATCH);
        assertThat(nie.matchedNif()).isEqualTo("X1234567L");

        VerificationResult cif = CertificateNifExtractor.verify(Map.of("organizationId", "b-09767898"), "B09767898");
        assertThat(cif.status()).isEqualTo(Status.MATCH);
        assertThat(cif.matchedNif()).isEqualTo("B09767898");
    }

    @Test
    void matchesVatesAndIdcesIdentifiers() {
        Map<String, Object> vatesCert = Map.of(
                "subject", "O=BRASATECH DIGITAL SOLUTIONS SLU, 2.5.4.97=VATES-B09767898, C=ES");
        VerificationResult vates = CertificateNifExtractor.verify(vatesCert, "B09767898");
        assertThat(vates.status()).isEqualTo(Status.MATCH);
        assertThat(vates.candidates()).containsExactly("B09767898");

        Map<String, Object> idcesCert = Map.of(
                "subject", "CN=PEREZ GARCIA LUIS, SERIALNUMBER=IDCES-12345678Z, C=ES");
        VerificationResult idces = CertificateNifExtractor.verify(idcesCert, "12345678Z");
        assertThat(idces.status()).isEqualTo(Status.MATCH);
        assertThat(idces.candidates()).containsExactly("12345678Z");
    }

    @Test
    void matchesCompanyCertificateWithBothRepresentativeAndCompanyNif() {
        Map<String, Object> companyRepCert = Map.of(
                "subject", Map.of(
                        "CN", "GARCIA LOPEZ ANA - 12345678Z",
                        "SERIALNUMBER", "IDCES-12345678Z",
                        "organizationIdentifier", "VATES-B09767898",
                        "O", "RESTAURANTE EJEMPLO S.L."
                ),
                "extensions", List.of("oid:2.5.4.97=VATES-B09767898", "issuer:FNMT-RCM"));

        VerificationResult matchCompany = CertificateNifExtractor.verify(companyRepCert, "B09767898");
        assertThat(matchCompany.status()).isEqualTo(Status.MATCH);
        assertThat(matchCompany.matchedNif()).isEqualTo("B09767898");
        assertThat(matchCompany.candidates()).containsExactlyInAnyOrder("12345678Z", "B09767898");

        VerificationResult mismatchOtherCompany = CertificateNifExtractor.verify(companyRepCert, "A28015865");
        assertThat(mismatchOtherCompany.status()).isEqualTo(Status.MISMATCH);
        assertThat(mismatchOtherCompany.matchedNif()).isNull();
        assertThat(mismatchOtherCompany.candidates()).containsExactlyInAnyOrder("12345678Z", "B09767898");
    }

    @Test
    void returnsMismatchWhenValidNifExtractedDoesNotMatchExpected() {
        Map<String, Object> cert = Map.of("subject", "SERIALNUMBER=IDCES-12345678Z");
        VerificationResult result = CertificateNifExtractor.verify(cert, "B09767898");
        assertThat(result.status()).isEqualTo(Status.MISMATCH);
        assertThat(result.matchedNif()).isNull();
        assertThat(result.candidates()).containsExactly("12345678Z");
    }

    @Test
    void failsClosedWithUnverifiedWhenNoValidNifCanBeExtracted() {
        assertThat(CertificateNifExtractor.verify(null, "B09767898").status())
                .isEqualTo(Status.UNVERIFIED);
        assertThat(CertificateNifExtractor.verify(Map.of(), "B09767898").status())
                .isEqualTo(Status.UNVERIFIED);
        assertThat(CertificateNifExtractor.verify(Map.of("issuer", "AC FNMT Usuarios", "serial", "99887766"), "B09767898").status())
                .isEqualTo(Status.UNVERIFIED);
        // Invalid checksums must not count as candidates
        assertThat(CertificateNifExtractor.verify(Map.of("nif", "12345678A", "org", "VATES-B09767899"), "B09767898").status())
                .isEqualTo(Status.UNVERIFIED);
    }

    @Test
    void ignoresIssuerDnOrganizationIdentifier() {
        Map<String, Object> cert = Map.of(
                "holder", "CN=PEREZ GARCIA LUIS - 12345678Z, 2.5.4.97=VATES-B09767898, O=RESTAURANTE EJEMPLO SL, C=ES",
                "issuer", "CN=AC Representación,OU=CERES,O=FNMT-RCM,C=ES,2.5.4.97=VATES-Q2826004J",
                "serialNumber", "1122334455");

        VerificationResult againstCaNif = CertificateNifExtractor.verify(cert, "Q2826004J");
        assertThat(againstCaNif.status()).isNotEqualTo(Status.MATCH);
        assertThat(againstCaNif.status()).isEqualTo(Status.MISMATCH);
        assertThat(againstCaNif.candidates()).doesNotContain("Q2826004J");
        assertThat(againstCaNif.candidates()).containsExactlyInAnyOrder("12345678Z", "B09767898");

        Map<String, Object> issuerOnly = Map.of(
                "issuer", "CN=AC Representación,OU=CERES,O=FNMT-RCM,C=ES,2.5.4.97=VATES-Q2826004J");
        VerificationResult issuerOnlyResult = CertificateNifExtractor.verify(issuerOnly, "Q2826004J");
        assertThat(issuerOnlyResult.status()).isEqualTo(Status.UNVERIFIED);
        assertThat(issuerOnlyResult.candidates()).isEmpty();
    }
}
