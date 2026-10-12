package es.brasatech.fastbite.verifactu.aeat;

import es.brasatech.fastbite.domain.verifactu.VerifactuStatus;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * What the AEAT answered to a submission (RespuestaRegFactuSistemaFacturacion in
 * RespuestaSuministro.xsd): the state of the whole submission, how long to wait before the next
 * one, and the state of each record.
 *
 * @param csv     the AEAT's secure verification code of the submission; absent when it was refused
 * @param state   Correcto, ParcialmenteCorrecto or Incorrecto
 * @param waitSeconds how long the AEAT asks to wait before the next submission
 */
record AeatResponse(String csv, String state, int waitSeconds, List<Line> lines) {

    /** Seconds to wait when the answer does not say. */
    static final int DEFAULT_WAIT = 60;

    /**
     * The answer for one record.
     *
     * @param invoiceNumber the NumSerieFactura it answers for
     * @param error         code and description, when the AEAT reports one
     */
    record Line(String invoiceNumber, VerifactuStatus status, String error) {
    }

    /** A SOAP fault or an answer that is not one: the whole submission was not processed. */
    static class Fault extends RuntimeException {
        Fault(String message) {
            super(message);
        }
    }

    static AeatResponse parse(String body) {
        Element root = document(body).getDocumentElement();
        Element fault = first(root, "Fault");
        if (fault != null) {
            String code = textOf(fault, "faultcode");
            String reason = textOf(fault, "faultstring");
            throw new Fault("AEAT refused the submission: " + (code == null ? "" : code + " ")
                    + (reason == null ? "(no reason given)" : reason));
        }
        Element answer = first(root, "RespuestaRegFactuSistemaFacturacion");
        if (answer == null) {
            throw new Fault("AEAT answer not understood: " + abbreviate(body));
        }
        List<Line> lines = new ArrayList<>();
        for (Element line : children(answer, "RespuestaLinea")) {
            lines.add(line(line));
        }
        return new AeatResponse(textOf(answer, "CSV"), textOf(answer, "EstadoEnvio"),
                waitSeconds(textOf(answer, "TiempoEsperaEnvio")), List.copyOf(lines));
    }

    private static Line line(Element line) {
        Element invoice = first(line, "IDFactura");
        String number = invoice == null ? null : textOf(invoice, "NumSerieFactura");
        String state = textOf(line, "EstadoRegistro");
        String code = textOf(line, "CodigoErrorRegistro");
        String description = textOf(line, "DescripcionErrorRegistro");
        String error = code == null && description == null ? null
                : (code == null ? "" : code + ": ") + (description == null ? "" : description);
        Element duplicate = first(line, "RegistroDuplicado");
        if (duplicate != null) {
            // Sent before, for instance when an earlier answer was lost: the AEAT already has it
            String kept = textOf(duplicate, "EstadoRegistroDuplicado");
            if ("Correcta".equals(kept)) {
                return new Line(number, VerifactuStatus.ACCEPTED, null);
            }
            if ("AceptadaConErrores".equals(kept)) {
                return new Line(number, VerifactuStatus.ACCEPTED_WITH_ERRORS,
                        joined(textOf(duplicate, "CodigoErrorRegistro"), textOf(duplicate, "DescripcionErrorRegistro")));
            }
        }
        VerifactuStatus status = switch (state == null ? "" : state) {
            case "Correcto" -> VerifactuStatus.ACCEPTED;
            case "AceptadoConErrores" -> VerifactuStatus.ACCEPTED_WITH_ERRORS;
            default -> VerifactuStatus.REJECTED;
        };
        if (status == VerifactuStatus.REJECTED && error == null) {
            error = "Refused by the AEAT" + (state == null ? "" : " (" + state + ")");
        }
        return new Line(number, status, error);
    }

    private static String joined(String code, String description) {
        if (code == null && description == null) {
            return null;
        }
        return (code == null ? "" : code + ": ") + (description == null ? "" : description);
    }

    private static int waitSeconds(String value) {
        try {
            return value == null || value.isBlank() ? DEFAULT_WAIT : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_WAIT;
        }
    }

    private static Document document(String body) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(body)));
        } catch (Exception e) {
            throw new Fault("AEAT answer is not XML: " + abbreviate(body));
        }
    }

    /** The first descendant with this local name, whatever prefix the AEAT gives it. */
    private static Element first(Element parent, String name) {
        var nodes = parent.getElementsByTagNameNS("*", name);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())) {
                found.add(element);
            }
        }
        return found;
    }

    private static String textOf(Element parent, String name) {
        Element element = first(parent, name);
        if (element == null) {
            return null;
        }
        String text = element.getTextContent().strip();
        return text.isEmpty() ? null : text;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "(empty)";
        }
        String flat = body.replaceAll("\\s+", " ").strip();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }
}
