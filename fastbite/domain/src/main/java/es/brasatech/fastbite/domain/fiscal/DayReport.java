package es.brasatech.fastbite.domain.fiscal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * What a location took on a business day, worked out from the tickets each time it is asked for:
 * nothing of it is stored, so it can be printed again for any day.
 *
 * <p>Sales and refunds count by how they were paid, tips included. A full invoice issued in place of
 * a ticket counts nothing: the ticket it replaces already did. A ticket cancelled because there was
 * no sale counts nothing either; it is listed apart, with the money given back.
 *
 * @param methods   money taken and given back, by payment method; only methods used that day
 * @param vat       the VAT of sales and refunds, rate by rate, highest rate first
 * @param series    the numbers issued in each series, cancelled tickets and replacements included
 * @param net       sales less refunds, VAT included, tips not
 * @param tips      tips taken less tips given back
 * @param salesCount sale tickets that count, cancelled ones not
 */
public record DayReport(LocalDate day, List<MethodTotal> methods, List<VatBreakdown.Line> vat, BigDecimal vatBase,
        BigDecimal vatAmount, List<SeriesRange> series, BigDecimal net, BigDecimal tips, int salesCount,
        List<FiscalTicket> refunds, List<Cancelled> cancelled, List<FiscalTicket> replacements) {

    /** Money taken (sales) and given back (refunds, negative) with one payment method, tips included. */
    public record MethodTotal(PaymentMethod method, BigDecimal sales, BigDecimal refunds) {
        public BigDecimal net() {
            return sales.add(refunds);
        }
    }

    /** The first and last number issued in a series that day, and how many. */
    public record SeriesRange(String series, String first, String last, int count) {
    }

    /** A ticket issued by mistake and cancelled: nothing was sold, and any money was given back. */
    public record Cancelled(FiscalTicket ticket, TicketCancellation cancellation) {
    }

    public static DayReport of(LocalDate day, List<FiscalTicket> issued, List<TicketCancellation> cancellations) {
        Map<String, TicketCancellation> cancelledById = cancellations.stream()
                .collect(Collectors.toMap(TicketCancellation::ticketId, c -> c, (a, b) -> a, LinkedHashMap::new));
        Map<PaymentMethod, BigDecimal[]> byMethod = new EnumMap<>(PaymentMethod.class);
        Map<BigDecimal, BigDecimal[]> byRate = new TreeMap<>(Comparator.reverseOrder());
        Map<String, List<FiscalTicket>> bySeries = new LinkedHashMap<>();
        List<FiscalTicket> refunds = new ArrayList<>();
        List<FiscalTicket> replacements = new ArrayList<>();
        List<Cancelled> cancelled = new ArrayList<>();
        BigDecimal net = zero();
        BigDecimal tips = zero();
        int salesCount = 0;

        for (FiscalTicket ticket : issued.stream().sorted(Comparator.comparing(FiscalTicket::issuedAt)).toList()) {
            bySeries.computeIfAbsent(ticket.series(), s -> new ArrayList<>()).add(ticket);
            if (ticket.kind() == TicketKind.REPLACEMENT) {
                replacements.add(ticket);
                continue;
            }
            TicketCancellation cancellation = cancelledById.get(ticket.id());
            if (cancellation != null) {
                cancelled.add(new Cancelled(ticket, cancellation));
                continue;
            }
            BigDecimal[] money = byMethod.computeIfAbsent(ticket.paymentMethod(), m -> new BigDecimal[]{zero(), zero()});
            if (ticket.refund()) {
                refunds.add(ticket);
                money[1] = money[1].add(ticket.charged());
            } else {
                salesCount++;
                money[0] = money[0].add(ticket.charged());
            }
            net = net.add(ticket.total());
            tips = tips.add(ticket.tip());
            if (ticket.vat() != null) {
                for (VatBreakdown.Line line : ticket.vat().lines()) {
                    BigDecimal[] sum = byRate.computeIfAbsent(line.rate().setScale(2, RoundingMode.HALF_UP),
                            r -> new BigDecimal[]{zero(), zero(), zero()});
                    sum[0] = sum[0].add(line.base());
                    sum[1] = sum[1].add(line.vat());
                    sum[2] = sum[2].add(line.gross());
                }
            }
        }

        List<MethodTotal> methods = byMethod.entrySet().stream()
                .map(e -> new MethodTotal(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
        List<VatBreakdown.Line> vat = byRate.entrySet().stream()
                .filter(e -> e.getValue()[2].signum() != 0 || e.getValue()[1].signum() != 0)
                .map(e -> new VatBreakdown.Line(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2])).toList();
        List<SeriesRange> series = bySeries.entrySet().stream().map(e -> {
            List<FiscalTicket> numbered = e.getValue().stream().sorted(Comparator.comparingInt(FiscalTicket::number)).toList();
            return new SeriesRange(e.getKey(), numbered.getFirst().fullNumber(), numbered.getLast().fullNumber(), numbered.size());
        }).sorted(Comparator.comparing(SeriesRange::series)).toList();
        return new DayReport(day, methods, vat,
                vat.stream().map(VatBreakdown.Line::base).reduce(zero(), BigDecimal::add),
                vat.stream().map(VatBreakdown.Line::vat).reduce(zero(), BigDecimal::add),
                series, net, tips, salesCount, List.copyOf(refunds), List.copyOf(cancelled), List.copyOf(replacements));
    }

    /** Cash taken less cash given back: what the sales of the day put in the drawer. */
    public BigDecimal cashNet() {
        return method(PaymentMethod.CASH).map(MethodTotal::net).orElse(zero());
    }

    public BigDecimal cashSales() {
        return method(PaymentMethod.CASH).map(MethodTotal::sales).orElse(zero());
    }

    public BigDecimal cashRefunds() {
        return method(PaymentMethod.CASH).map(MethodTotal::refunds).orElse(zero());
    }

    /** Everything taken less everything given back, tips included. */
    public BigDecimal takings() {
        return methods.stream().map(MethodTotal::net).reduce(zero(), BigDecimal::add);
    }

    /** Whether nothing at all was issued that day. */
    public boolean empty() {
        return series.isEmpty();
    }

    private java.util.Optional<MethodTotal> method(PaymentMethod method) {
        return methods.stream().filter(m -> m.method() == method).findFirst();
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    }
}
