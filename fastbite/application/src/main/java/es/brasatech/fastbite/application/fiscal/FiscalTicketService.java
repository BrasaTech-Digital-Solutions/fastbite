package es.brasatech.fastbite.application.fiscal;

import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.PaymentMethod;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.fiscal.TicketLine;
import es.brasatech.fastbite.domain.fiscal.TicketRecipient;
import es.brasatech.fastbite.domain.fiscal.TicketSeries;
import es.brasatech.fastbite.domain.fiscal.VatBreakdown;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.OrderStatus;
import es.brasatech.fastbite.domain.table.Table;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Takes payment for orders and issues the ticket that records it. Every payment produces exactly
 * one ticket, in the same transaction that marks the orders paid, so there is never a paid order
 * without its ticket or a ticket without its payment.
 */
@Service
@Transactional
public class FiscalTicketService {

    /** Shown when a production location tries to charge before its business details are complete. */
    public static final String NOT_READY = "This location cannot issue tickets yet. Complete its business details "
            + "in the owner console (Location settings → Business & tax).";

    /** How a payment was made. */
    public record Payment(PaymentMethod method, BigDecimal cashReceived, BigDecimal tip, String reference) {

        public static Payment of(PaymentMethod method, BigDecimal cashReceived) {
            return new Payment(method, cashReceived, BigDecimal.ZERO, null);
        }

        public Payment {
            // Negative only on refunds, which give the tip back too
            tip = tip == null ? BigDecimal.ZERO : tip.setScale(2, RoundingMode.HALF_UP);
            if (method != PaymentMethod.CASH) {
                cashReceived = null;
            }
        }
    }

    private final OrderService orders;
    private final FiscalTicketRepository tickets;
    private final FiscalProfileService profiles;
    private final PlatformEnvironment environment;
    private final TableService tables;
    private final Clock clock;

    @Autowired
    public FiscalTicketService(OrderService orders, FiscalTicketRepository tickets, FiscalProfileService profiles,
            PlatformEnvironment environment, TableService tables,
            @Value("${fastbite.orders.time-zone:Europe/Madrid}") ZoneId timeZone) {
        this(orders, tickets, profiles, environment, tables, Clock.system(timeZone));
    }

    public FiscalTicketService(OrderService orders, FiscalTicketRepository tickets, FiscalProfileService profiles,
            PlatformEnvironment environment, TableService tables, Clock clock) {
        this.orders = orders;
        this.tickets = tickets;
        this.profiles = profiles;
        this.environment = environment;
        this.tables = tables;
        this.clock = clock;
    }

    /**
     * Charges orders at the counter and issues their ticket: a full invoice when a customer is
     * given, a simplified one otherwise.
     *
     * @throws LocalizedRejection if an order cannot be charged, the payment does not cover it, or the
     *                            location cannot issue tickets yet
     */
    public FiscalTicket settle(List<String> orderIds, Payment payment, TicketRecipient recipient, String issuedBy) {
        if (orderIds == null || orderIds.isEmpty()) {
            throw new LocalizedRejection("ticket.error.noOrders", "Choose at least one order to charge");
        }
        if (payment.method() == PaymentMethod.ONLINE) {
            throw new LocalizedRejection("ticket.error.onlineAtCounter", "Online payments are recorded when the guest pays online");
        }
        requireReady();
        List<Order> charged = new ArrayList<>();
        for (String orderId : new LinkedHashSet<>(orderIds)) {
            Order order = orders.findById(orderId)
                    .orElseThrow(() -> new LocalizedRejection("ticket.error.orderMissing", "An order to charge no longer exists"));
            if (order.status() == OrderStatus.CANCELLED) {
                throw new LocalizedRejection("ticket.error.orderCancelled", "Order #" + order.orderNumber() + " was cancelled and cannot be charged", order.orderNumber());
            }
            if (order.paymentStatus() != OrderPaymentStatus.UNPAID || tickets.findSaleByOrderId(orderId).isPresent()) {
                throw new LocalizedRejection("ticket.error.orderPaid", "Order #" + order.orderNumber() + " is already paid", order.orderNumber());
            }
            charged.add(order);
        }
        TicketKind kind = recipient != null ? TicketKind.FULL : TicketKind.SIMPLIFIED;
        FiscalTicket ticket = issue(kind, charged, payment, recipient == null ? null : recipient.validated(), issuedBy);
        charged.forEach(order -> orders.markOrderPaid(order.id()));
        return ticket;
    }

    /**
     * Records an online payment confirmed by the payment provider and issues its ticket. Safe to
     * call again for the same order: the ticket issued the first time is returned.
     */
    public FiscalTicket settleOnline(String orderId, BigDecimal tip, String reference) {
        if (tip != null && tip.signum() < 0) {
            throw new LocalizedRejection("ticket.error.tip", "A tip cannot be negative");
        }
        Optional<FiscalTicket> issued = tickets.findSaleByOrderId(orderId);
        if (issued.isPresent()) {
            return issued.get();
        }
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Paid order " + orderId + " does not exist"));
        // The money has arrived: a missing business detail must not lose the record of it
        FiscalTicket ticket = issue(TicketKind.SIMPLIFIED, List.of(order),
                new Payment(PaymentMethod.ONLINE, null, tip, reference), null, "online");
        if (order.paymentStatus() != OrderPaymentStatus.PAID) {
            orders.markOrderPaid(orderId);
        }
        return ticket;
    }

    /**
     * Issues a full invoice in place of a simplified ticket, for a customer who asks for one after paying.
     */
    public FiscalTicket replace(String ticketId, TicketRecipient recipient, String issuedBy) {
        FiscalTicket original = find(ticketId);
        if (original.kind() != TicketKind.SIMPLIFIED) {
            throw new LocalizedRejection("ticket.error.replaceOnlySimplified", "Only a simplified ticket can be replaced by a full invoice");
        }
        tickets.findCorrection(ticketId, TicketKind.REPLACEMENT).ifPresent(done -> {
            throw new LocalizedRejection("ticket.error.alreadyReplaced", "Ticket " + original.fullNumber() + " was already replaced by invoice " + done.fullNumber(), original.fullNumber(), done.fullNumber());
        });
        tickets.findCorrection(ticketId, TicketKind.RECTIFYING).ifPresent(done -> {
            throw new LocalizedRejection("ticket.error.alreadyRefunded", "Ticket " + original.fullNumber() + " was refunded by " + done.fullNumber(), original.fullNumber(), done.fullNumber());
        });
        TicketRecipient customer = recipient == null ? null : recipient.validated();
        if (customer == null) {
            throw new LocalizedRejection("recipient.error.name", "Enter the customer's details for the invoice");
        }
        requireReady();
        return add(TicketKind.REPLACEMENT, original.training(), original.lines(), original.discount(), original.total(),
                new Payment(original.paymentMethod(), null, original.tip(), original.paymentReference()), customer,
                new FiscalTicket.Correction(original.id(), original.fullNumber(), original.issuedOn(), null),
                original.orderIds(), original.orderNumbers(), original.tableName(), issuedBy);
    }

    /**
     * Refunds a ticket in full by issuing a rectifying ticket that reverses it, and marks its orders refunded.
     */
    public FiscalTicket refund(String ticketId, String reason, String issuedBy) {
        FiscalTicket original = find(ticketId);
        if (original.kind() == TicketKind.RECTIFYING) {
            throw new LocalizedRejection("ticket.error.refundRefund", "A refund cannot be refunded");
        }
        if (reason == null || reason.isBlank() || reason.length() > 200) {
            throw new LocalizedRejection("ticket.error.refundReason", "Say why the ticket is refunded (up to 200 characters)");
        }
        tickets.findCorrection(ticketId, TicketKind.REPLACEMENT).ifPresent(replacement -> {
            throw new LocalizedRejection("ticket.error.refundReplacement", "Ticket " + original.fullNumber() + " was replaced by invoice "
                    + replacement.fullNumber() + ": refund that invoice instead", original.fullNumber(), replacement.fullNumber());
        });
        tickets.findCorrection(ticketId, TicketKind.RECTIFYING).ifPresent(done -> {
            throw new LocalizedRejection("ticket.error.alreadyRefunded", "Ticket " + original.fullNumber() + " was already refunded by " + done.fullNumber(), original.fullNumber(), done.fullNumber());
        });
        requireReady();
        FiscalTicket refund = add(TicketKind.RECTIFYING, original.training(),
                original.lines().stream().map(TicketLine::negated).toList(), original.discount().negate(),
                original.total().negate(),
                new Payment(original.paymentMethod(), null, original.tip().negate(), original.paymentReference()),
                original.recipient(),
                new FiscalTicket.Correction(original.id(), original.fullNumber(), original.issuedOn(), reason.strip()),
                original.orderIds(), original.orderNumbers(), original.tableName(), issuedBy);
        original.orderIds().forEach(orders::markOrderRefunded);
        return refund;
    }

    @Transactional(readOnly = true)
    public Optional<FiscalTicket> findById(String ticketId) {
        return tickets.findById(ticketId);
    }

    @Transactional(readOnly = true)
    public Optional<FiscalTicket> findForOrder(String orderId) {
        return tickets.findSaleByOrderId(orderId);
    }

    /** The replacement and the refund issued for a ticket, if any. */
    @Transactional(readOnly = true)
    public List<FiscalTicket> corrections(String ticketId) {
        List<FiscalTicket> found = new ArrayList<>();
        tickets.findCorrection(ticketId, TicketKind.REPLACEMENT).ifPresent(found::add);
        tickets.findCorrection(ticketId, TicketKind.RECTIFYING).ifPresent(found::add);
        return found;
    }

    /** Tickets issued on a business day, oldest first. */
    @Transactional(readOnly = true)
    public List<FiscalTicket> issuedOn(java.time.LocalDate day) {
        ZoneId zone = clock.getZone();
        return tickets.findIssuedBetween(day.atStartOfDay(zone).toOffsetDateTime(),
                day.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
    }

    /** Today in the location's time zone. */
    public java.time.LocalDate today() {
        return java.time.LocalDate.now(clock);
    }

    /** Whether this deployment issues training tickets (the sandbox). */
    public boolean training() {
        return environment.sandbox();
    }

    /** Whether payments can be taken: always in the sandbox, with complete business details in production. */
    @Transactional(readOnly = true)
    public boolean ready() {
        return environment.sandbox() || profiles.get().complete();
    }

    private void requireReady() {
        if (!ready()) {
            throw new LocalizedRejection("ticket.error.notReady", NOT_READY);
        }
    }

    private FiscalTicket find(String ticketId) {
        return tickets.findById(ticketId).orElseThrow(() -> new LocalizedRejection("ticket.error.notFound", "Ticket not found"));
    }

    private FiscalTicket issue(TicketKind kind, List<Order> charged, Payment payment, TicketRecipient recipient,
            String issuedBy) {
        BigDecimal defaultRate = profiles.get().defaultVatRate();
        List<TicketLine> lines = charged.stream()
                .flatMap(order -> order.items() == null ? java.util.stream.Stream.empty() : order.items().stream())
                .map(item -> TicketLine.of(item, defaultRate))
                .toList();
        BigDecimal total = charged.stream().map(Order::total).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal linesTotal = lines.stream().map(TicketLine::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = linesTotal.subtract(total).max(BigDecimal.ZERO);
        List<String> orderIds = charged.stream().map(Order::id).toList();
        String orderNumbers = charged.stream().map(order -> String.valueOf(order.orderNumber()))
                .distinct().collect(Collectors.joining(", "));
        Set<String> tableNames = new LinkedHashSet<>();
        orderIds.forEach(id -> tables.findTableByOrderId(id).map(Table::name).ifPresent(tableNames::add));
        return add(kind, environment.sandbox(), lines, discount, total, payment, recipient, null, orderIds,
                orderNumbers, tableNames.isEmpty() ? null : String.join(", ", tableNames), issuedBy);
    }

    private FiscalTicket add(TicketKind kind, boolean training, List<TicketLine> lines, BigDecimal discount,
            BigDecimal total, Payment payment, TicketRecipient recipient, FiscalTicket.Correction corrects,
            List<String> orderIds, String orderNumbers, String tableName, String issuedBy) {
        FiscalProfile profile = profiles.get();
        OffsetDateTime now = OffsetDateTime.now(clock);
        String prefix = profile.seriesPrefix() != null ? profile.seriesPrefix() : "DEMO";
        String series = TicketSeries.of(prefix, kind, now.toLocalDate(), training);
        VatBreakdown vat = VatBreakdown.of(lines.stream()
                .map(line -> new VatBreakdown.Amount(line.total(), line.vatRate())).toList(), discount.abs());
        BigDecimal change = null;
        if (payment.cashReceived() != null) {
            change = payment.cashReceived().subtract(total.add(payment.tip())).setScale(2, RoundingMode.HALF_UP);
            if (change.signum() < 0) {
                throw new LocalizedRejection("ticket.error.cashShort", "The cash received does not cover the total");
            }
        }
        int number = tickets.nextNumber(series);
        return tickets.add(new FiscalTicket(UUID.randomUUID().toString(), kind, series, number,
                TicketSeries.fullNumber(series, number), training, now, issuedBy, FiscalTicket.Issuer.of(profile),
                recipient, List.copyOf(lines), discount, total, vat, payment.tip(), payment.method(),
                payment.cashReceived(), change, corrects, List.copyOf(orderIds), orderNumbers, tableName,
                payment.reference()));
    }
}
