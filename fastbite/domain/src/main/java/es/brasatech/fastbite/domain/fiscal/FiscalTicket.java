package es.brasatech.fastbite.domain.fiscal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * An invoice exactly as it was issued. Tickets are never changed or deleted: a mistake or a refund
 * is corrected by issuing a rectifying ticket, and a later request for a full invoice by issuing a
 * replacement.
 *
 * @param series     the series it is numbered in, such as KHC-T26 (training tickets start with TEST-)
 * @param fullNumber series and number, such as KHC-T26-000123
 * @param training   issued in the sandbox: no fiscal value, never sent to the tax agency
 * @param issuedAt   when it was issued, in the location's time zone
 * @param issuer     the business details printed on it, as they were when it was issued
 * @param recipient  the customer, on full invoices and replacements only
 * @param total      what the lines come to after the discount, VAT included; negative on refunds
 * @param tip        voluntary tip paid on top of the total; not part of the VAT base
 * @param corrects   the ticket a rectifying ticket or replacement refers to
 * @param orderIds   the orders it charges for
 * @param paymentReference the payment provider's reference for online payments, for refunds
 */
public record FiscalTicket(
        String id,
        TicketKind kind,
        String series,
        int number,
        String fullNumber,
        boolean training,
        OffsetDateTime issuedAt,
        String issuedBy,
        Issuer issuer,
        TicketRecipient recipient,
        List<TicketLine> lines,
        BigDecimal discount,
        BigDecimal total,
        VatBreakdown vat,
        BigDecimal tip,
        PaymentMethod paymentMethod,
        BigDecimal cashReceived,
        BigDecimal change,
        Correction corrects,
        List<String> orderIds,
        String orderNumbers,
        String tableName,
        String paymentReference) {

    /** The business details printed on a ticket. */
    public record Issuer(String legalName, String tradeName, String taxId, String addressLine, String postalCode,
            String city, String province, String phone) {

        public static Issuer of(FiscalProfile profile) {
            return new Issuer(profile.legalName(), profile.tradeName(), profile.taxId(), profile.addressLine(),
                    profile.postalCode(), profile.city(), profile.province(), profile.phone());
        }

        public String displayName() {
            return tradeName != null ? tradeName : legalName;
        }
    }

    /** What a rectifying ticket or a replacement refers to. */
    public record Correction(String ticketId, String fullNumber, LocalDate issuedOn, String reason) {
    }

    /** What the guest handed over in total: the ticket total plus the tip. */
    public BigDecimal charged() {
        return total.add(tip);
    }

    public boolean refund() {
        return kind == TicketKind.RECTIFYING;
    }

    /** The date printed on the ticket. */
    public LocalDate issuedOn() {
        return issuedAt.toLocalDate();
    }
}
