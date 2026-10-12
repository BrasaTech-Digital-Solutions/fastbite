package es.brasatech.fastbite.verifactu.aeat;

import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;

import static es.brasatech.fastbite.verifactu.aeat.AeatFixtures.SYSTEM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AeatRequestTest {

    @Test
    void theMessageFollowsTheAeatSchemas() throws Exception {
        String xml = AeatRequest.envelope(SYSTEM, "1", AeatFixtures.chain());

        Element message = body(parse(xml));
        // Throws with the line and reason if anything breaks the official schemas
        schema().newValidator().validate(new DOMSource(message));
        assertThat(message.getLocalName()).isEqualTo("RegFactuSistemaFacturacion");
    }

    @Test
    void theRecordsCarryWhatTheFingerprintWasComputedFrom() throws Exception {
        var chain = AeatFixtures.chain();
        Document doc = parse(AeatRequest.envelope(SYSTEM, "1", chain));

        assertThat(texts(doc, "Huella")).containsSubsequence(chain.get(0).record().fingerprint(),
                chain.get(1).record().fingerprint());
        assertThat(texts(doc, "FechaHoraHusoGenRegistro")).containsOnly("2026-10-09T13:00:05+02:00");
        assertThat(texts(doc, "PrimerRegistro")).containsExactly("S");
        assertThat(texts(doc, "TipoFactura")).containsExactly("F2", "F1", "F1", "F3", "R5");
        assertThat(texts(doc, "ImporteTotal")).containsExactly("14.63", "22.00", "5.50", "14.63", "-11.00");
        assertThat(texts(doc, "TipoImpositivo")).startsWith("21.00", "10.00");
    }

    @Test
    void correctionsAndReplacementsNameTheOriginalInvoice() throws Exception {
        Document doc = parse(AeatRequest.envelope(SYSTEM, "1", AeatFixtures.chain()));

        assertThat(texts(doc, "TipoRectificativa")).containsExactly("I");
        Element rectified = (Element) doc.getElementsByTagNameNS("*", "IDFacturaRectificada").item(0);
        Element replaced = (Element) doc.getElementsByTagNameNS("*", "IDFacturaSustituida").item(0);
        assertThat(rectified.getTextContent()).contains("KH-T26-000001", "09-10-2026");
        assertThat(replaced.getTextContent()).contains("KH-T26-000001");
    }

    @Test
    void customersAreNamedOnFullInvoicesOnly() throws Exception {
        Document doc = parse(AeatRequest.envelope(SYSTEM, "1", AeatFixtures.chain()));

        // F1, F1 and F3 name the customer; the simplified invoice and its correction do not
        assertThat(doc.getElementsByTagNameNS("*", "IDDestinatario").getLength()).isEqualTo(3);
        assertThat(texts(doc, "IDType")).containsExactly("02");
        assertThat(texts(doc, "CodigoPais")).containsExactly("FR");
    }

    @Test
    void oneSubmissionCarriesOneIssuer() {
        var other = AeatFixtures.submission(AeatFixtures.ticket(
                new FiscalTicket.Issuer("Ana García", null, "12345678Z", "Calle Mayor 1", "28013", "Madrid", "Madrid", null),
                TicketKind.SIMPLIFIED, "AG-T26-000001", null, null, "1.00", "10"), "F2", null);

        assertThatThrownBy(() -> AeatRequest.envelope(SYSTEM, "1", List.of(AeatFixtures.chain().getFirst(), other)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Schema schema() throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setResourceResolver(new LocalSchemas());
        return factory.newSchema(AeatRequestTest.class.getResource("/aeat/SuministroLR.xsd"));
    }

    static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static Element body(Document doc) {
        Element body = (Element) doc.getElementsByTagNameNS(AeatRequest.SOAP, "Body").item(0);
        for (var node = body.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                return element;
            }
        }
        throw new AssertionError("Empty SOAP body");
    }

    static List<String> texts(Document doc, String name) {
        var nodes = doc.getElementsByTagNameNS("*", name);
        List<String> texts = new java.util.ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            texts.add(nodes.item(i).getTextContent());
        }
        return texts;
    }

    /** Serves the schemas from test resources, so the tests never go online. */
    static class LocalSchemas implements LSResourceResolver {
        @Override
        public LSInput resolveResource(String type, String namespace, String publicId, String systemId, String baseUri) {
            if (systemId == null) {
                return null;
            }
            String name = systemId.substring(systemId.lastIndexOf('/') + 1);
            InputStream in = name.endsWith(".dtd") ? new java.io.ByteArrayInputStream(new byte[0])
                    : AeatRequestTest.class.getResourceAsStream("/aeat/" + name);
            if (in == null) {
                return null;
            }
            return new Input(publicId, systemId, baseUri, in);
        }
    }

    record Input(String publicId, String systemId, String baseURI, InputStream byteStream) implements LSInput {
        public Reader getCharacterStream() { return null; }
        public void setCharacterStream(Reader characterStream) { }
        public InputStream getByteStream() { return byteStream; }
        public void setByteStream(InputStream byteStream) { }
        public String getStringData() { return null; }
        public void setStringData(String stringData) { }
        public String getSystemId() { return systemId; }
        public void setSystemId(String systemId) { }
        public String getPublicId() { return publicId; }
        public void setPublicId(String publicId) { }
        public String getBaseURI() { return baseURI; }
        public void setBaseURI(String baseURI) { }
        public String getEncoding() { return null; }
        public void setEncoding(String encoding) { }
        public boolean getCertifiedText() { return false; }
        public void setCertifiedText(boolean certifiedText) { }
    }
}
