package es.brasatech.fastbite.verifactu.aeat;

import es.brasatech.fastbite.application.verifactu.VerifactuSubmitter;
import es.brasatech.fastbite.application.verifactu.VerifactuSystem;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.TicketRecipient;
import es.brasatech.fastbite.domain.fiscal.VatBreakdown;
import es.brasatech.fastbite.domain.verifactu.Verifactu;
import es.brasatech.fastbite.domain.verifactu.VerifactuRecord;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/**
 * The SOAP message that sends billing records to the AEAT (RegFactuSistemaFacturacion), as the
 * schemas SuministroLR.xsd and SuministroInformacion.xsd describe it. Only registros de alta are
 * written, subsanaciones included: FastBite corrects an invoice by issuing a corrective one, never
 * by cancelling it.
 */
final class AeatRequest {

    static final String SOAP = "http://schemas.xmlsoap.org/soap/envelope/";
    static final String BASE = "https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/aplicaciones/es/aeat/tike/cont/ws/";
    /** SuministroLR.xsd: the message and its records. */
    static final String LR = BASE + "SuministroLR.xsd";
    /** SuministroInformacion.xsd: everything inside them. */
    static final String SF = BASE + "SuministroInformacion.xsd";

    /** Countries whose customers are identified by their EU VAT number (IDType 02). */
    private static final Set<String> EU = Set.of("AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "FI", "FR", "GR",
            "HR", "HU", "IE", "IT", "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK", "XI");

    private static final BigDecimal MACRODATO = new BigDecimal("100000000");

    private AeatRequest() {
    }

    /**
     * The whole SOAP message for records of one issuer, in chain order.
     *
     * @param installation the installation of the location's chain the records belong to
     */
    static String envelope(VerifactuSystem system, VerifactuSubmitter.Installation installation,
            List<VerifactuSubmitter.Submission> records) {
        if (records.isEmpty() || records.size() > 1000) {
            throw new IllegalArgumentException("A submission carries 1 to 1000 records, not " + records.size());
        }
        VerifactuRecord first = records.getFirst().record();
        StringWriter out = new StringWriter();
        try {
            XMLStreamWriter xml = XMLOutputFactory.newInstance().createXMLStreamWriter(out);
            xml.writeStartDocument("UTF-8", "1.0");
            xml.setPrefix("soapenv", SOAP);
            xml.setPrefix("sum", LR);
            xml.setPrefix("sum1", SF);
            xml.writeStartElement(SOAP, "Envelope");
            xml.writeNamespace("soapenv", SOAP);
            xml.writeNamespace("sum", LR);
            xml.writeNamespace("sum1", SF);
            xml.writeEmptyElement(SOAP, "Header");
            xml.writeStartElement(SOAP, "Body");
            xml.writeStartElement(LR, "RegFactuSistemaFacturacion");

            xml.writeStartElement(LR, "Cabecera");
            xml.writeStartElement(SF, "ObligadoEmision");
            text(xml, "NombreRazon", max(first.issuerName(), 120));
            text(xml, "NIF", first.issuerNif());
            xml.writeEndElement();
            xml.writeEndElement();

            for (VerifactuSubmitter.Submission submission : records) {
                if (!submission.record().issuerNif().equals(first.issuerNif())) {
                    throw new IllegalArgumentException("A submission carries the records of one issuer only");
                }
                xml.writeStartElement(LR, "RegistroFactura");
                alta(xml, system, installation, submission.record(), submission.ticket());
                xml.writeEndElement();
            }

            xml.writeEndElement(); // RegFactuSistemaFacturacion
            xml.writeEndElement(); // Body
            xml.writeEndElement(); // Envelope
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Could not write the billing records", e);
        }
        return out.toString();
    }

    private static void alta(XMLStreamWriter xml, VerifactuSystem system, VerifactuSubmitter.Installation installation,
            VerifactuRecord record,
            FiscalTicket ticket) throws XMLStreamException {
        String type = record.invoiceType();
        xml.writeStartElement(SF, "RegistroAlta");
        text(xml, "IDVersion", "1.0");
        xml.writeStartElement(SF, "IDFactura");
        text(xml, "IDEmisorFactura", record.issuerNif());
        text(xml, "NumSerieFactura", record.invoiceNumber());
        text(xml, "FechaExpedicionFactura", Verifactu.date(record.issueDate()));
        xml.writeEndElement();
        text(xml, "RefExterna", record.id());
        text(xml, "NombreRazonEmisor", max(record.issuerName(), 120));
        if (record.amendment()) {
            // Subsanación; X when the agency refused the invoice before and so does not have it yet
            text(xml, "Subsanacion", "S");
            if (record.amends().afterRejection()) {
                text(xml, "RechazoPrevio", "X");
            }
        }
        text(xml, "TipoFactura", type);
        if (type.startsWith("R")) {
            // By differences: the corrective invoice carries the negative amounts of the refund
            text(xml, "TipoRectificativa", "I");
            invoices(xml, "FacturasRectificadas", "IDFacturaRectificada", record.corrected());
        } else if ("F3".equals(type)) {
            invoices(xml, "FacturasSustituidas", "IDFacturaSustituida", record.corrected());
        }
        text(xml, "DescripcionOperacion", max(system.operationDescription(), 500));
        if (record.amountTotal().abs().compareTo(MACRODATO) >= 0) {
            text(xml, "Macrodato", "S");
        }
        if (!"F2".equals(type) && !"R5".equals(type) && ticket.recipient() != null) {
            xml.writeStartElement(SF, "Destinatarios");
            recipient(xml, ticket.recipient());
            xml.writeEndElement();
        }
        breakdown(xml, ticket.vat());
        text(xml, "CuotaTotal", Verifactu.amount(record.taxTotal()));
        text(xml, "ImporteTotal", Verifactu.amount(record.amountTotal()));
        xml.writeStartElement(SF, "Encadenamiento");
        if (record.firstOfChain()) {
            text(xml, "PrimerRegistro", "S");
        } else {
            VerifactuRecord.Link previous = record.previous();
            xml.writeStartElement(SF, "RegistroAnterior");
            text(xml, "IDEmisorFactura", previous.issuerNif());
            text(xml, "NumSerieFactura", previous.invoiceNumber());
            text(xml, "FechaExpedicionFactura", Verifactu.date(previous.issueDate()));
            text(xml, "Huella", previous.fingerprint());
            xml.writeEndElement();
        }
        xml.writeEndElement();
        system(xml, system, installation);
        // Exactly the value the fingerprint was computed from
        text(xml, "FechaHoraHusoGenRegistro", Verifactu.timestamp(record.generatedAt()));
        text(xml, "TipoHuella", "01");
        text(xml, "Huella", record.fingerprint());
        xml.writeEndElement();
    }

    private static void invoices(XMLStreamWriter xml, String list, String item, VerifactuRecord.Link invoice)
            throws XMLStreamException {
        if (invoice == null) {
            return;
        }
        xml.writeStartElement(SF, list);
        xml.writeStartElement(SF, item);
        text(xml, "IDEmisorFactura", invoice.issuerNif());
        text(xml, "NumSerieFactura", invoice.invoiceNumber());
        text(xml, "FechaExpedicionFactura", Verifactu.date(invoice.issueDate()));
        xml.writeEndElement();
        xml.writeEndElement();
    }

    private static void recipient(XMLStreamWriter xml, TicketRecipient recipient) throws XMLStreamException {
        xml.writeStartElement(SF, "IDDestinatario");
        text(xml, "NombreRazon", max(recipient.name(), 120));
        if ("ES".equals(recipient.country())) {
            text(xml, "NIF", recipient.taxId());
        } else {
            xml.writeStartElement(SF, "IDOtro");
            text(xml, "CodigoPais", recipient.country());
            // 02: EU VAT number; 04: an official identity document of another country
            text(xml, "IDType", EU.contains(recipient.country()) ? "02" : "04");
            text(xml, "ID", max(recipient.taxId(), 20));
            xml.writeEndElement();
        }
        xml.writeEndElement();
    }

    /** One line per VAT rate: VAT (01), general regime (01), subject and not exempt (S1). */
    private static void breakdown(XMLStreamWriter xml, VatBreakdown vat) throws XMLStreamException {
        xml.writeStartElement(SF, "Desglose");
        List<VatBreakdown.Line> lines = vat == null ? List.of() : vat.lines();
        if (lines.isEmpty()) {
            // A ticket for nothing still needs one line
            lines = List.of(new VatBreakdown.Line(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        }
        for (VatBreakdown.Line line : lines) {
            xml.writeStartElement(SF, "DetalleDesglose");
            text(xml, "Impuesto", "01");
            text(xml, "ClaveRegimen", "01");
            text(xml, "CalificacionOperacion", "S1");
            text(xml, "TipoImpositivo", line.rate().setScale(2, RoundingMode.HALF_UP).toPlainString());
            text(xml, "BaseImponibleOimporteNoSujeto", Verifactu.amount(line.base()));
            text(xml, "CuotaRepercutida", Verifactu.amount(line.vat()));
            xml.writeEndElement();
        }
        xml.writeEndElement();
    }

    /**
     * FastBite works only as VERI*FACTU and can serve many taxpayers (TipoUsoPosibleMultiOT). Each
     * location's chain is its own installation; IndicadorMultiplesOT says whether its owner has
     * more than one location (AEAT developer FAQ, section 4).
     */
    private static void system(XMLStreamWriter xml, VerifactuSystem system,
            VerifactuSubmitter.Installation installation) throws XMLStreamException {
        xml.writeStartElement(SF, "SistemaInformatico");
        text(xml, "NombreRazon", max(system.producerName(), 120));
        text(xml, "NIF", system.producerNif());
        text(xml, "NombreSistemaInformatico", max(system.systemName(), 30));
        text(xml, "IdSistemaInformatico", max(system.systemId(), 2));
        text(xml, "Version", max(system.version(), 50));
        text(xml, "NumeroInstalacion", max(installation.number(), 100));
        text(xml, "TipoUsoPosibleSoloVerifactu", "S");
        text(xml, "TipoUsoPosibleMultiOT", "S");
        text(xml, "IndicadorMultiplesOT", installation.multipleBillings() ? "S" : "N");
        xml.writeEndElement();
    }

    private static void text(XMLStreamWriter xml, String name, String value) throws XMLStreamException {
        xml.writeStartElement(SF, name);
        xml.writeCharacters(value == null ? "" : value);
        xml.writeEndElement();
    }

    private static String max(String value, int length) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        return trimmed.length() <= length ? trimmed : trimmed.substring(0, length);
    }
}
