package es.brasatech.fastbite.domain.verifactu;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * The billing record (registro de facturación de alta) a VERI*FACTU system generates for each
 * invoice it issues, chained to the record before it by its fingerprint. The fiscal content of a
 * record never changes; only its submission status does.
 *
 * @param chain        records are chained per location; training records from the sandbox form their own chain
 * @param sequence     position in the chain, from 1
 * @param invoiceType  AEAT invoice type: F1 full, F2 simplified, F3 replacing simplified invoices, R1/R5 corrective
 * @param previous     the record this one is chained to; null for the first record of the chain
 * @param fingerprint  SHA-256 "huella" of this record, uppercase hexadecimal
 * @param qrUrl        the AEAT address printed as a QR code on the invoice, where anyone can check it
 * @param corrected    for F3 and corrective invoices, the invoice they replace or correct
 * @param amends       for a subsanación, the record of the same invoice it amends; null otherwise
 */
public record VerifactuRecord(
        String id,
        String ticketId,
        String chain,
        int sequence,
        String invoiceType,
        String issuerNif,
        String issuerName,
        String invoiceNumber,
        LocalDate issueDate,
        OffsetDateTime generatedAt,
        BigDecimal taxTotal,
        BigDecimal amountTotal,
        Link previous,
        String fingerprint,
        String qrUrl,
        Link corrected,
        Amendment amends,
        boolean training,
        Submission submission) {

    /** Identifies another record: issuer, invoice number, issue date and, for the chain, its fingerprint. */
    public record Link(String issuerNif, String invoiceNumber, LocalDate issueDate, String fingerprint) {
    }

    /**
     * What a subsanación amends: an earlier record of the same invoice, which stays in the chain
     * unchanged. The amending record carries the corrected data and its own fingerprint.
     *
     * @param afterRejection the tax agency never accepted the invoice, so it does not have it yet
     *                       (RechazoPrevio X)
     */
    public record Amendment(String recordId, boolean afterRejection) {
    }

    /** Where the record stands with the tax agency. */
    public record Submission(VerifactuStatus status, int attempts, String error, String csv, OffsetDateTime at) {

        public static final Submission PENDING = new Submission(VerifactuStatus.PENDING, 0, null, null, null);
    }

    public static final String REAL_CHAIN = "REAL";
    public static final String TRAINING_CHAIN = "TRAINING";

    public boolean firstOfChain() {
        return previous == null;
    }

    /** A subsanación of an earlier record of the same invoice. */
    public boolean amendment() {
        return amends != null;
    }

    /** Refused, or accepted with errors: it can be sent again as a subsanación. */
    public boolean amendable() {
        return submission.status() == VerifactuStatus.REJECTED
                || submission.status() == VerifactuStatus.ACCEPTED_WITH_ERRORS;
    }

    public VerifactuRecord withSubmission(Submission submission) {
        return new VerifactuRecord(id, ticketId, chain, sequence, invoiceType, issuerNif, issuerName, invoiceNumber,
                issueDate, generatedAt, taxTotal, amountTotal, previous, fingerprint, qrUrl, corrected, amends, training,
                submission);
    }
}
