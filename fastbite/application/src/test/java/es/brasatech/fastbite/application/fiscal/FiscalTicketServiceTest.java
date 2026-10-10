package es.brasatech.fastbite.application.fiscal;

import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.PaymentMethod;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.fiscal.TicketRecipient;
import es.brasatech.fastbite.domain.order.CartItem;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.product.ProductCustomizer;
import es.brasatech.fastbite.domain.table.Table;
import es.brasatech.fastbite.domain.table.TableStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FiscalTicketServiceTest {

    /** Tickets kept in memory, numbered like the database does. */
    static class MemoryTickets implements FiscalTicketRepository {
        final Map<String, Integer> series = new HashMap<>();
        final List<FiscalTicket> issued = new ArrayList<>();

        public int nextNumber(String name) { return series.merge(name, 1, Integer::sum); }
        public FiscalTicket add(FiscalTicket ticket) { issued.add(ticket); return ticket; }
        public Optional<FiscalTicket> findById(String id) { return issued.stream().filter(t -> t.id().equals(id)).findFirst(); }
        public Optional<FiscalTicket> findSaleByOrderId(String orderId) {
            return issued.stream().filter(t -> (t.kind() == TicketKind.SIMPLIFIED || t.kind() == TicketKind.FULL)
                    && t.orderIds().contains(orderId)).findFirst();
        }
        public Optional<FiscalTicket> findCorrection(String ticketId, TicketKind kind) {
            return issued.stream().filter(t -> t.kind() == kind && t.corrects() != null
                    && t.corrects().ticketId().equals(ticketId)).findFirst();
        }
        public List<FiscalTicket> findIssuedBetween(OffsetDateTime from, OffsetDateTime to) {
            return issued.stream().filter(t -> !t.issuedAt().isBefore(from) && t.issuedAt().isBefore(to)).toList();
        }
        public boolean anyRealTicket() { return issued.stream().anyMatch(t -> !t.training()); }
    }

    static final FiscalProfile ANA = new FiscalProfile("Ana García López", "Kebab House", "12345678Z", "Calle Mayor 12",
            "28013", "Madrid", "Madrid", null, null, "KH", new BigDecimal("10"));
    static final Clock NOON = Clock.fixed(Instant.parse("2026-10-09T11:00:00Z"), ZoneId.of("Europe/Madrid"));

    OrderService orders = mock(OrderService.class);
    FiscalProfileService profiles = mock(FiscalProfileService.class);
    TableService tables = mock(TableService.class);
    MemoryTickets tickets = new MemoryTickets();
    es.brasatech.fastbite.application.verifactu.VerifactuService verifactu =
            mock(es.brasatech.fastbite.application.verifactu.VerifactuService.class);
    FiscalTicketService production = new FiscalTicketService(orders, tickets, profiles,
            new PlatformEnvironment("production"), tables, verifactu, NOON);
    FiscalTicketService sandbox = new FiscalTicketService(orders, tickets, profiles,
            new PlatformEnvironment("sandbox"), tables, verifactu, NOON);

    Order kebab;
    Order beer;

    @BeforeEach
    void setUp() {
        when(profiles.get()).thenReturn(ANA);
        kebab = order(12, new CartItem("l1", "kebab", "Kebab", null, null, 2,
                List.of(new ProductCustomizer("garlic", "Garlic sauce", new BigDecimal("0.50"), 1)), new BigDecimal("6.50"),
                new BigDecimal("10.00")));
        beer = order(13, new CartItem("l2", "beer", "Beer to go", null, null, 1, List.of(), new BigDecimal("3.63"),
                new BigDecimal("21.00")));
        when(tables.findTableByOrderId(kebab.id())).thenReturn(Optional.of(
                new Table("t4", "Terrace 4", 4, TableStatus.OCCUPIED, true, List.of(kebab.id(), beer.id()))));
    }

    private Order order(int number, CartItem line) {
        Order order = new Order(List.of(line), number, OrderPaymentStatus.UNPAID, OrderChannel.TABLE, "es");
        when(orders.findById(order.id())).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    void aTableBillIsOneTicketWithItsVatPerRateAndTheChange() {
        FiscalTicket ticket = production.settle(List.of(kebab.id(), beer.id()),
                FiscalTicketService.Payment.of(PaymentMethod.CASH, new BigDecimal("20.00")), null, "carla");

        assertThat(ticket.kind()).isEqualTo(TicketKind.SIMPLIFIED);
        assertThat(ticket.fullNumber()).isEqualTo("KH-T26-000001");
        assertThat(ticket.training()).isFalse();
        assertThat(ticket.issuedOn()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(ticket.issuedAt().getOffset().getId()).isEqualTo("+02:00");
        assertThat(ticket.total()).isEqualByComparingTo("17.63"); // 2 x (6.50 + 0.50) + 3.63
        assertThat(ticket.vat().lines()).hasSize(2);
        assertThat(ticket.vat().lines().get(0).rate()).isEqualByComparingTo("21");
        assertThat(ticket.vat().lines().get(0).vat()).isEqualByComparingTo("0.63");
        assertThat(ticket.vat().lines().get(1).vat()).isEqualByComparingTo("1.27");
        assertThat(ticket.change()).isEqualByComparingTo("2.37");
        assertThat(ticket.issuer().taxId()).isEqualTo("12345678Z");
        assertThat(ticket.tableName()).isEqualTo("Terrace 4");
        assertThat(ticket.orderNumbers()).isEqualTo("12, 13");
        assertThat(ticket.lines().getFirst().options()).extracting(TicketLine -> TicketLine.name()).containsExactly("Garlic sauce");
        verify(orders).markOrderPaid(kebab.id());
        verify(orders).markOrderPaid(beer.id());
        verify(verifactu).record(ticket);
    }

    @Test
    void ticketsAreNumberedOneAfterAnotherAndFullInvoicesHaveTheirOwnSeries() {
        production.settle(List.of(kebab.id()), FiscalTicketService.Payment.of(PaymentMethod.CARD, null), null, "carla");
        FiscalTicket invoice = production.settle(List.of(beer.id()), FiscalTicketService.Payment.of(PaymentMethod.CARD, null),
                new TicketRecipient("b12345674", "es", "Acme SL", "Calle Real 1"), "carla");

        assertThat(tickets.issued).extracting(FiscalTicket::fullNumber).containsExactly("KH-T26-000001", "KH-F26-000001");
        assertThat(invoice.kind()).isEqualTo(TicketKind.FULL);
        assertThat(invoice.recipient().taxId()).isEqualTo("B12345674");
        assertThat(invoice.cashReceived()).isNull();
    }

    @Test
    void anOrderIsChargedOnceAndNeverWithoutEnoughCash() {
        production.settle(List.of(kebab.id()), FiscalTicketService.Payment.of(PaymentMethod.CARD, null), null, "carla");
        when(orders.findById(kebab.id())).thenReturn(Optional.of(kebab.setPaymentStatus(OrderPaymentStatus.PAID)));

        assertThatThrownBy(() -> production.settle(List.of(kebab.id()),
                FiscalTicketService.Payment.of(PaymentMethod.CARD, null), null, "carla"))
                .isInstanceOf(LocalizedRejection.class).hasMessageContaining("already paid");
        assertThatThrownBy(() -> production.settle(List.of(beer.id()),
                FiscalTicketService.Payment.of(PaymentMethod.CASH, new BigDecimal("1.00")), null, "carla"))
                .isInstanceOf(LocalizedRejection.class).hasMessageContaining("cash");
        assertThat(tickets.issued).hasSize(1);
        verify(orders, never()).markOrderPaid(beer.id());
    }

    @Test
    void productionRefusesToChargeWithoutCompleteBusinessDetails() {
        when(profiles.get()).thenReturn(FiscalProfile.EMPTY);

        assertThat(production.ready()).isFalse();
        assertThatThrownBy(() -> production.settle(List.of(kebab.id()),
                FiscalTicketService.Payment.of(PaymentMethod.CARD, null), null, "carla"))
                .isInstanceOf(LocalizedRejection.class);
        verify(orders, never()).markOrderPaid(any());
    }

    @Test
    void theSandboxIssuesTrainingTicketsInTheirOwnSeriesEvenWithoutDetails() {
        when(profiles.get()).thenReturn(FiscalProfile.EMPTY);

        FiscalTicket ticket = sandbox.settle(List.of(kebab.id()), FiscalTicketService.Payment.of(PaymentMethod.CARD, null),
                null, "carla");

        assertThat(sandbox.ready()).isTrue();
        assertThat(ticket.training()).isTrue();
        assertThat(ticket.fullNumber()).isEqualTo("TEST-DEMO-T26-000001");
        assertThat(tickets.anyRealTicket()).isFalse();
    }

    @Test
    void anOnlinePaymentIsRecordedOnceWithItsTip() {
        FiscalTicket first = production.settleOnline(kebab.id(), new BigDecimal("1.40"), "pi_123");
        FiscalTicket again = production.settleOnline(kebab.id(), new BigDecimal("1.40"), "pi_123");

        assertThat(again).isSameAs(first);
        assertThat(first.paymentMethod()).isEqualTo(PaymentMethod.ONLINE);
        assertThat(first.tip()).isEqualByComparingTo("1.40");
        assertThat(first.charged()).isEqualByComparingTo("15.40");
        assertThat(first.paymentReference()).isEqualTo("pi_123");
        assertThat(tickets.issued).hasSize(1);
    }

    @Test
    void aRefundReversesTheWholeTicketOnceAndMarksTheOrdersRefunded() {
        FiscalTicket sale = production.settleOnline(kebab.id(), new BigDecimal("1.40"), "pi_123");

        FiscalTicket refund = production.refund(sale.id(), "Wrong dish", "manager");

        assertThat(refund.kind()).isEqualTo(TicketKind.RECTIFYING);
        assertThat(refund.fullNumber()).isEqualTo("KH-R26-000001");
        assertThat(refund.total()).isEqualByComparingTo("-14.00");
        assertThat(refund.tip()).isEqualByComparingTo("-1.40");
        assertThat(refund.vat().vat()).isEqualByComparingTo("-1.27");
        assertThat(refund.corrects().fullNumber()).isEqualTo(sale.fullNumber());
        assertThat(refund.corrects().reason()).isEqualTo("Wrong dish");
        verify(orders).markOrderRefunded(kebab.id());
        assertThatThrownBy(() -> production.refund(sale.id(), "Again", "manager")).isInstanceOf(LocalizedRejection.class);
        assertThatThrownBy(() -> production.refund(refund.id(), "Undo", "manager")).isInstanceOf(LocalizedRejection.class);
        assertThatThrownBy(() -> production.refund(sale.id(), " ", "manager")).isInstanceOf(LocalizedRejection.class);
    }

    @Test
    void aSimplifiedTicketCanBeReplacedByAFullInvoiceOnceWhichIsThenTheOneToRefund() {
        FiscalTicket sale = production.settle(List.of(kebab.id()), FiscalTicketService.Payment.of(PaymentMethod.CARD, null),
                null, "carla");

        FiscalTicket invoice = production.replace(sale.id(), new TicketRecipient("FR12345678901", "FR", "Dupont SARL", null),
                "carla");

        assertThat(invoice.kind()).isEqualTo(TicketKind.REPLACEMENT);
        assertThat(invoice.fullNumber()).isEqualTo("KH-F26-000001");
        assertThat(invoice.total()).isEqualByComparingTo(sale.total());
        assertThat(invoice.corrects().ticketId()).isEqualTo(sale.id());
        assertThatThrownBy(() -> production.replace(sale.id(), new TicketRecipient("B12345674", "ES", "Acme", null), "carla"))
                .isInstanceOf(LocalizedRejection.class);
        assertThatThrownBy(() -> production.refund(sale.id(), "Wrong", "manager"))
                .isInstanceOf(LocalizedRejection.class).hasMessageContaining(invoice.fullNumber());
        assertThat(production.refund(invoice.id(), "Wrong", "manager").kind()).isEqualTo(TicketKind.RECTIFYING);
    }

    @Test
    void aFullInvoiceNeedsAValidCustomer() {
        assertThatThrownBy(() -> production.settle(List.of(kebab.id()), FiscalTicketService.Payment.of(PaymentMethod.CARD, null),
                new TicketRecipient("12345678A", "ES", "Ana", null), "carla"))
                .isInstanceOf(LocalizedRejection.class).extracting(e -> ((LocalizedRejection) e).key())
                .isEqualTo("recipient.error.taxIdInvalid");
        assertThat(tickets.issued).isEmpty();
    }
}
