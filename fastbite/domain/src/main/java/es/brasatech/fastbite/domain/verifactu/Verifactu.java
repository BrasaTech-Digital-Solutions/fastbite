package es.brasatech.fastbite.domain.verifactu;

import es.brasatech.fastbite.domain.fiscal.TicketKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/**
 * The rules of VERI*FACTU (Real Decreto 1007/2023 and Orden HAC/1177/2024) that FastBite applies
 * itself: invoice types, the fingerprint that chains records, and the QR code printed on invoices.
 */
public final class Verifactu {

    /** The AEAT page that checks an invoice from its QR code. */
    public static final String QR_PRODUCTION = "https://www2.agenciatributaria.gob.es/wlpl/TIKE-CONT/ValidarQR";
    /** The same page in the AEAT test environment, used by the sandbox. */
    public static final String QR_TEST = "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQR";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private Verifactu() {
    }

    /**
     * The AEAT invoice type of a ticket. A correction of a simplified invoice is R5; of a full
     * invoice R1 (art. 80 of the VAT law: the sale was undone).
     *
     * @param correctedKind the kind of the ticket a corrective ticket corrects; ignored otherwise
     */
    public static String invoiceType(TicketKind kind, TicketKind correctedKind) {
        return switch (kind) {
            case SIMPLIFIED -> "F2";
            case FULL -> "F1";
            case REPLACEMENT -> "F3";
            case RECTIFYING -> correctedKind == TicketKind.SIMPLIFIED ? "R5" : "R1";
        };
    }

    /** Dates as the AEAT writes them: 09-10-2026. */
    public static String date(LocalDate date) {
        return date.format(DATE);
    }

    /** Amounts as the AEAT writes them: two decimals and a point, negative with a minus sign. */
    public static String amount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** The generation time of a record: to the second, with its UTC offset. */
    public static String timestamp(OffsetDateTime time) {
        return time.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * The fingerprint (huella) of a record of an issued invoice: the SHA-256 of its key fields and
     * of the previous record's fingerprint, joined as name=value pairs, in uppercase hexadecimal.
     */
    public static String fingerprint(String issuerNif, String invoiceNumber, LocalDate issueDate, String invoiceType,
            BigDecimal taxTotal, BigDecimal amountTotal, String previousFingerprint, OffsetDateTime generatedAt) {
        String input = "IDEmisorFactura=" + issuerNif.trim()
                + "&NumSerieFactura=" + invoiceNumber.trim()
                + "&FechaExpedicionFactura=" + date(issueDate)
                + "&TipoFactura=" + invoiceType
                + "&CuotaTotal=" + amount(taxTotal)
                + "&ImporteTotal=" + amount(amountTotal)
                + "&Huella=" + (previousFingerprint == null ? "" : previousFingerprint)
                + "&FechaHoraHusoGenRegistro=" + timestamp(generatedAt);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Whether a record is correctly chained (Orden HAC/1177/2024, art. 7.i): its fingerprint
     * matches its own data, and it points at the record before it, by invoice and fingerprint.
     *
     * @param previous the record before it in the chain; null when it is the first
     */
    public static boolean chained(VerifactuRecord record, VerifactuRecord previous) {
        VerifactuRecord.Link link = record.previous();
        String expected = fingerprint(record.issuerNif(), record.invoiceNumber(), record.issueDate(),
                record.invoiceType(), record.taxTotal(), record.amountTotal(), link == null ? null : link.fingerprint(),
                record.generatedAt());
        if (!expected.equals(record.fingerprint())) {
            return false;
        }
        if (previous == null) {
            return link == null;
        }
        return link != null && previous.fingerprint().equals(link.fingerprint())
                && previous.invoiceNumber().equals(link.invoiceNumber())
                && previous.issuerNif().equals(link.issuerNif())
                && previous.issueDate().equals(link.issueDate());
    }

    /** The QR address of an invoice: the AEAT page that shows whether it was reported. */
    public static String qrUrl(boolean production, String issuerNif, String invoiceNumber, LocalDate issueDate,
            BigDecimal amountTotal) {
        return (production ? QR_PRODUCTION : QR_TEST)
                + "?nif=" + encode(issuerNif)
                + "&numserie=" + encode(invoiceNumber)
                + "&fecha=" + date(issueDate)
                + "&importe=" + amount(amountTotal);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
