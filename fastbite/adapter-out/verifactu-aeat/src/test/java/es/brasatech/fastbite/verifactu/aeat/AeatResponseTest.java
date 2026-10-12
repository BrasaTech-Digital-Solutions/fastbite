package es.brasatech.fastbite.verifactu.aeat;

import es.brasatech.fastbite.domain.verifactu.VerifactuStatus;
import org.junit.jupiter.api.Test;

import javax.xml.transform.stream.StreamSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AeatResponseTest {

    static final String R = AeatRequest.BASE + "RespuestaSuministro.xsd";

    /** An answer shaped as the AEAT sends it. */
    static String answer(String state, String wait, String... lines) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <env:Envelope xmlns:env="http://schemas.xmlsoap.org/soap/envelope/"><env:Header/><env:Body Id="Body">
                <tikR:RespuestaRegFactuSistemaFacturacion xmlns:tikR="%s" xmlns:tik="%s">
                <tikR:CSV>A-TEST-CSV</tikR:CSV>
                <tikR:DatosPresentacion><tik:NIFPresentador>B12345674</tik:NIFPresentador>
                <tik:TimestampPresentacion>2026-10-09T13:00:07+02:00</tik:TimestampPresentacion></tikR:DatosPresentacion>
                <tikR:Cabecera><tik:ObligadoEmision><tik:NombreRazon>Ana García &amp; Hijos S.L.</tik:NombreRazon>
                <tik:NIF>B12345674</tik:NIF></tik:ObligadoEmision></tikR:Cabecera>
                <tikR:TiempoEsperaEnvio>%s</tikR:TiempoEsperaEnvio><tikR:EstadoEnvio>%s</tikR:EstadoEnvio>%s
                </tikR:RespuestaRegFactuSistemaFacturacion></env:Body></env:Envelope>"""
                .formatted(R, AeatRequest.SF, wait, state, String.join("", lines)).strip();
    }

    static String line(String number, String state, String code, String description) {
        return """
                <tikR:RespuestaLinea><tikR:IDFactura><tik:IDEmisorFactura>B12345674</tik:IDEmisorFactura>
                <tik:NumSerieFactura>%s</tik:NumSerieFactura><tik:FechaExpedicionFactura>09-10-2026</tik:FechaExpedicionFactura>
                </tikR:IDFactura><tikR:Operacion><tik:TipoOperacion>Alta</tik:TipoOperacion></tikR:Operacion>
                <tikR:EstadoRegistro>%s</tikR:EstadoRegistro>%s%s</tikR:RespuestaLinea>"""
                .formatted(number, state,
                        code == null ? "" : "<tikR:CodigoErrorRegistro>" + code + "</tikR:CodigoErrorRegistro>",
                        description == null ? "" : "<tikR:DescripcionErrorRegistro>" + description + "</tikR:DescripcionErrorRegistro>");
    }

    @Test
    void theSampleAnswerFollowsTheAeatSchema() throws Exception {
        String xml = answer("ParcialmenteCorrecto", "60", line("KH-T26-000001", "Correcto", null, null),
                line("KH-T26-000002", "Incorrecto", "1100", "Valor o tipo incorrecto"));
        var doc = AeatRequestTest.parse(xml);
        var answer = doc.getElementsByTagNameNS(R, "RespuestaRegFactuSistemaFacturacion").item(0);

        var factory = javax.xml.validation.SchemaFactory.newInstance(javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setResourceResolver(new AeatRequestTest.LocalSchemas());
        factory.newSchema(new StreamSource(AeatResponseTest.class.getResource("/aeat/RespuestaSuministro.xsd").toString()))
                .newValidator().validate(new javax.xml.transform.dom.DOMSource(answer));
    }

    @Test
    void readsTheStateOfEachRecord() {
        var response = AeatResponse.parse(answer("ParcialmenteCorrecto", "120",
                line("KH-T26-000001", "Correcto", null, null),
                line("KH-T26-000002", "AceptadoConErrores", "2005", "Aviso"),
                line("KH-T26-000003", "Incorrecto", "1100", "Valor o tipo incorrecto")));

        assertThat(response.csv()).isEqualTo("A-TEST-CSV");
        assertThat(response.state()).isEqualTo("ParcialmenteCorrecto");
        assertThat(response.waitSeconds()).isEqualTo(120);
        assertThat(response.lines()).containsExactly(
                new AeatResponse.Line("KH-T26-000001", VerifactuStatus.ACCEPTED, null),
                new AeatResponse.Line("KH-T26-000002", VerifactuStatus.ACCEPTED_WITH_ERRORS, "2005: Aviso"),
                new AeatResponse.Line("KH-T26-000003", VerifactuStatus.REJECTED, "1100: Valor o tipo incorrecto"));
    }

    @Test
    void aRecordTheAeatAlreadyHasCountsAsAccepted() {
        String duplicate = line("KH-T26-000001", "Incorrecto", "3000", "Registro de facturación duplicado")
                .replace("</tikR:RespuestaLinea>", """
                        <tikR:RegistroDuplicado><tik:IdPeticionRegistroDuplicado>2026100912</tik:IdPeticionRegistroDuplicado>
                        <tik:EstadoRegistroDuplicado>Correcta</tik:EstadoRegistroDuplicado></tikR:RegistroDuplicado>
                        </tikR:RespuestaLinea>""");

        var response = AeatResponse.parse(answer("Incorrecto", "60", duplicate));

        assertThat(response.lines()).singleElement().satisfies(line -> {
            assertThat(line.status()).isEqualTo(VerifactuStatus.ACCEPTED);
            assertThat(line.error()).isNull();
        });
    }

    @Test
    void faultsAndNonsenseAreReported() {
        assertThatThrownBy(() -> AeatResponse.parse("""
                <env:Envelope xmlns:env="http://schemas.xmlsoap.org/soap/envelope/"><env:Body><env:Fault>
                <faultcode>env:Server</faultcode><faultstring>Error interno</faultstring></env:Fault></env:Body></env:Envelope>"""))
                .isInstanceOf(AeatResponse.Fault.class).hasMessageContaining("Error interno");
        assertThatThrownBy(() -> AeatResponse.parse("<html>Service unavailable</html>"))
                .isInstanceOf(AeatResponse.Fault.class).hasMessageContaining("not understood");
        assertThatThrownBy(() -> AeatResponse.parse(""))
                .isInstanceOf(AeatResponse.Fault.class);
    }

    @Test
    void aMissingWaitMeansSixtySeconds() {
        assertThat(AeatResponse.parse(answer("Correcto", "")).waitSeconds()).isEqualTo(AeatResponse.DEFAULT_WAIT);
    }
}
