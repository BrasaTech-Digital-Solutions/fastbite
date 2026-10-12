package es.brasatech.fastbite.verifactu.aeat;

import es.brasatech.fastbite.application.verifactu.VerifactuSubmitter;
import es.brasatech.fastbite.application.verifactu.VerifactuSystem;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.PaymentMethod;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.fiscal.TicketRecipient;
import es.brasatech.fastbite.domain.fiscal.VatBreakdown;
import es.brasatech.fastbite.domain.verifactu.Verifactu;
import es.brasatech.fastbite.domain.verifactu.VerifactuRecord;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Tickets and their records, chained as the service would chain them. */
final class AeatFixtures {

    static final VerifactuSystem SYSTEM = new VerifactuSystem("Brasatech", "B12345674", "FastBite", "FB", "1.0",
            "Servicios de restauración");
    static final FiscalTicket.Issuer ISSUER = new FiscalTicket.Issuer("Ana García & Hijos S.L.", "Kebab House",
            "B12345674", "Calle Mayor 1", "28013", "Madrid", "Madrid", null);
    static final VerifactuSubmitter.Installation INSTALLATION =
            new VerifactuSubmitter.Installation("kebab-R-20261009110000", false);
    static final OffsetDateTime NOON = OffsetDateTime.parse("2026-10-09T13:00:05+02:00");

    private AeatFixtures() {
    }

    static FiscalTicket ticket(TicketKind kind, String number, TicketRecipient recipient, FiscalTicket.Correction corrects,
            String... grossAndRate) {
        return ticket(ISSUER, kind, number, recipient, corrects, grossAndRate);
    }

    static FiscalTicket ticket(FiscalTicket.Issuer issuer, TicketKind kind, String number, TicketRecipient recipient,
            FiscalTicket.Correction corrects, String... grossAndRate) {
        return ticket(NOON.minusSeconds(5), issuer, kind, number, recipient, corrects, grossAndRate);
    }

    static FiscalTicket ticket(OffsetDateTime issuedAt, FiscalTicket.Issuer issuer, TicketKind kind, String number,
            TicketRecipient recipient, FiscalTicket.Correction corrects, String... grossAndRate) {
        List<VatBreakdown.Amount> amounts = new java.util.ArrayList<>();
        for (int i = 0; i < grossAndRate.length; i += 2) {
            amounts.add(new VatBreakdown.Amount(new BigDecimal(grossAndRate[i]), new BigDecimal(grossAndRate[i + 1])));
        }
        VatBreakdown vat = VatBreakdown.of(amounts);
        return new FiscalTicket("ticket-" + number, kind, number.substring(0, number.lastIndexOf('-')), 1, number,
                false, issuedAt, "carla", issuer, recipient, List.of(), BigDecimal.ZERO, vat.gross(), vat,
                BigDecimal.ZERO, PaymentMethod.CARD, null, null, corrects, List.of("o1"), null, null, null);
    }

    /** The record of a ticket, chained to the previous record (or the first of its chain). */
    static VerifactuSubmitter.Submission submission(FiscalTicket ticket, String type, VerifactuRecord previous) {
        return submission(ticket, type, previous, NOON);
    }

    static VerifactuSubmitter.Submission submission(FiscalTicket ticket, String type, VerifactuRecord previous,
            OffsetDateTime generatedAt) {
        VerifactuRecord.Link link = previous == null ? null : new VerifactuRecord.Link(previous.issuerNif(),
                previous.invoiceNumber(), previous.issueDate(), previous.fingerprint());
        VerifactuRecord.Link corrected = ticket.corrects() == null ? null : new VerifactuRecord.Link(
                ticket.issuer().taxId(), ticket.corrects().fullNumber(), ticket.corrects().issuedOn(), null);
        String fingerprint = Verifactu.fingerprint(ticket.issuer().taxId(), ticket.fullNumber(), ticket.issuedOn(), type,
                ticket.vat().vat(), ticket.total(), link == null ? null : link.fingerprint(), generatedAt);
        VerifactuRecord record = new VerifactuRecord("record-" + ticket.fullNumber(), ticket.id(), VerifactuRecord.REAL_CHAIN,
                previous == null ? 1 : previous.sequence() + 1, type, ticket.issuer().taxId(), ticket.issuer().legalName(),
                ticket.fullNumber(), ticket.issuedOn(), generatedAt, ticket.vat().vat(), ticket.total(), link, fingerprint,
                Verifactu.qrUrl(false, ticket.issuer().taxId(), ticket.fullNumber(), ticket.issuedOn(), ticket.total()),
                corrected, null, false, VerifactuRecord.Submission.PENDING);
        return new VerifactuSubmitter.Submission(record, ticket);
    }

    /** A subsanación of a record, chained after it, under the issuer's corrected name. */
    static VerifactuSubmitter.Submission amendment(VerifactuSubmitter.Submission original, boolean afterRejection) {
        return amendment(original, afterRejection, "Ana García e Hijos S.L.", original.record().generatedAt().plusMinutes(10));
    }

    static VerifactuSubmitter.Submission amendment(VerifactuSubmitter.Submission original, boolean afterRejection,
            String issuerName, OffsetDateTime generatedAt) {
        VerifactuRecord r = original.record();
        VerifactuRecord.Link previous = new VerifactuRecord.Link(r.issuerNif(), r.invoiceNumber(), r.issueDate(),
                r.fingerprint());
        String fingerprint = Verifactu.fingerprint(r.issuerNif(), r.invoiceNumber(), r.issueDate(), r.invoiceType(),
                r.taxTotal(), r.amountTotal(), r.fingerprint(), generatedAt);
        return new VerifactuSubmitter.Submission(new VerifactuRecord("amends-" + r.id(), r.ticketId(), r.chain(),
                r.sequence() + 1, r.invoiceType(), r.issuerNif(), issuerName, r.invoiceNumber(),
                r.issueDate(), generatedAt, r.taxTotal(), r.amountTotal(), previous, fingerprint, r.qrUrl(), r.corrected(),
                new VerifactuRecord.Amendment(r.id(), afterRejection), false, VerifactuRecord.Submission.PENDING),
                original.ticket());
    }

    /** A sale, a full invoice to a Spanish and one to a French customer, a replacement and a refund. */
    static List<VerifactuSubmitter.Submission> chain() {
        var sale = ticket(TicketKind.SIMPLIFIED, "KH-T26-000001", null, null, "11.00", "10", "3.63", "21");
        var s1 = submission(sale, "F2", null);
        var full = ticket(TicketKind.FULL, "KH-F26-000001",
                new TicketRecipient("12345678Z", "ES", "Luis Pérez", "Calle Sol 2"), null, "22.00", "10");
        var s2 = submission(full, "F1", s1.record());
        var foreign = ticket(TicketKind.FULL, "KH-F26-000002",
                new TicketRecipient("FR12345678901", "FR", "Café de Paris SARL", "Rue X"), null, "5.50", "10");
        var s3 = submission(foreign, "F1", s2.record());
        var replacement = ticket(TicketKind.REPLACEMENT, "KH-F26-000003",
                new TicketRecipient("12345678Z", "ES", "Luis Pérez", null),
                new FiscalTicket.Correction(sale.id(), sale.fullNumber(), sale.issuedOn(), null), "11.00", "10", "3.63", "21");
        var s4 = submission(replacement, "F3", s3.record());
        var refund = ticket(TicketKind.RECTIFYING, "KH-R26-000001", null,
                new FiscalTicket.Correction(sale.id(), sale.fullNumber(), sale.issuedOn(), "Wrong dish"), "-11.00", "10");
        var s5 = submission(refund, "R5", s4.record());
        return List.of(s1, s2, s3, s4, s5);
    }
}
