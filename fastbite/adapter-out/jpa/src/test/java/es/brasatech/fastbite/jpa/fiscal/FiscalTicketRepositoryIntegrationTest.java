package es.brasatech.fastbite.jpa.fiscal;

import es.brasatech.fastbite.application.fiscal.FiscalTicketRepository;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.PaymentMethod;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.fiscal.TicketLine;
import es.brasatech.fastbite.domain.fiscal.TicketRecipient;
import es.brasatech.fastbite.domain.fiscal.VatBreakdown;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.TestConfig;
import es.brasatech.fastbite.jpa.tenant.TenantProvisionerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TestConfig.class)
@ActiveProfiles("jpa")
class FiscalTicketRepositoryIntegrationTest {

    @Autowired FiscalTicketRepository tickets;
    @Autowired TenantProvisionerAdapter provisioner;
    @Autowired TransactionTemplate transactions;

    @AfterEach void clear() { TenantContext.clear(); }

    private String tenant() {
        String tenant = "tickets" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        provisioner.provisionTenant(tenant);
        return tenant;
    }

    private <T> T in(String tenant, Supplier<T> work) {
        TenantContext.setCurrentTenant(tenant);
        try { return transactions.execute(status -> work.get()); } finally { TenantContext.clear(); }
    }

    private static FiscalTicket ticket(TicketKind kind, int number, String orderId, FiscalTicket.Correction corrects) {
        var line = new TicketLine("Kebab", 2, new BigDecimal("7.00"), new BigDecimal("14.00"), new BigDecimal("10.00"),
                List.of(new TicketLine.Option("Garlic sauce", new BigDecimal("0.50"), 1)));
        var vat = VatBreakdown.of(List.of(new VatBreakdown.Amount(line.total(), line.vatRate())));
        String series = "KH-" + kind.seriesLetter() + "26";
        return new FiscalTicket(UUID.randomUUID().toString(), kind, series, number, series + "-" + String.format("%06d", number),
                false, OffsetDateTime.parse("2026-10-09T23:30:00+02:00"), "carla",
                new FiscalTicket.Issuer("Ana García", "Kebab House", "12345678Z", "Calle Mayor 12", "28013", "Madrid",
                        "Madrid", "+34 600 000 000"),
                kind == TicketKind.FULL ? new TicketRecipient("B12345674", "ES", "Acme SL", "Calle Real 1") : null,
                List.of(line), BigDecimal.ZERO, line.total(), vat, new BigDecimal("1.40"), PaymentMethod.CASH,
                new BigDecimal("20.00"), new BigDecimal("4.60"), corrects, List.of(orderId), "12", "Terrace 4", null);
    }

    @Test
    void numbersFollowEachOtherPerSeriesAndLocation() {
        String centre = tenant();
        String airport = tenant();

        assertThat(in(centre, () -> tickets.nextNumber("KH-T26"))).isEqualTo(1);
        assertThat(in(centre, () -> tickets.nextNumber("KH-T26"))).isEqualTo(2);
        assertThat(in(centre, () -> tickets.nextNumber("KH-F26"))).isEqualTo(1);
        assertThat(in(airport, () -> tickets.nextNumber("KH-T26"))).isEqualTo(1);
    }

    @Test
    void aNumberTakenInATransactionThatFailsIsGivenOutAgain() {
        String tenant = tenant();
        TenantContext.setCurrentTenant(tenant);
        try {
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                tickets.nextNumber("KH-T26");
                throw new IllegalStateException("payment failed");
            })).isInstanceOf(IllegalStateException.class);
        } finally {
            TenantContext.clear();
        }

        assertThat(in(tenant, () -> tickets.nextNumber("KH-T26"))).isEqualTo(1);
    }

    @Test
    void concurrentTicketsNeverShareANumber() throws Exception {
        String tenant = tenant();
        in(tenant, () -> tickets.nextNumber("KH-T26")); // the series exists, as after the first ticket
        Callable<Integer> take = () -> in(tenant, () -> tickets.nextNumber("KH-T26"));
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = pool.invokeAll(java.util.Collections.nCopies(12, take));
            List<Integer> numbers = new java.util.ArrayList<>();
            for (var future : futures) numbers.add(future.get());
            assertThat(numbers).doesNotHaveDuplicates().hasSize(12).allMatch(n -> n >= 2 && n <= 13);
        }
    }

    @Test
    void aTicketReadsBackExactlyAsIssued() {
        String tenant = tenant();
        FiscalTicket issued = ticket(TicketKind.FULL, 1, "order-1", null);
        in(tenant, () -> tickets.add(issued));

        FiscalTicket read = in(tenant, () -> tickets.findById(issued.id())).orElseThrow();

        assertThat(read).usingRecursiveComparison().ignoringFieldsOfTypes(BigDecimal.class).isEqualTo(issued);
        assertThat(read.total()).isEqualByComparingTo("14.00");
        assertThat(read.vat().vat()).isEqualByComparingTo("1.27");
        assertThat(read.issuedOn()).isEqualTo(java.time.LocalDate.of(2026, 10, 9));
        assertThat(in(tenant, () -> tickets.findSaleByOrderId("order-1"))).map(FiscalTicket::id).hasValue(issued.id());
        assertThat(in(tenant, tickets::anyRealTicket)).isTrue();
    }

    @Test
    void anOrderIsChargedByOneTicketOnly() {
        String tenant = tenant();
        in(tenant, () -> tickets.add(ticket(TicketKind.SIMPLIFIED, 1, "order-1", null)));

        assertThatThrownBy(() -> in(tenant, () -> tickets.add(ticket(TicketKind.SIMPLIFIED, 2, "order-1", null))))
                .isInstanceOf(RuntimeException.class);
        assertThat(in(tenant, () -> tickets.findIssuedBetween(OffsetDateTime.parse("2026-10-09T00:00:00+02:00"),
                OffsetDateTime.parse("2026-10-10T00:00:00+02:00")))).hasSize(1);
    }

    @Test
    void correctionsAreFoundFromTheTicketTheyCorrect() {
        String tenant = tenant();
        FiscalTicket sale = ticket(TicketKind.SIMPLIFIED, 1, "order-1", null);
        in(tenant, () -> tickets.add(sale));
        FiscalTicket refund = ticket(TicketKind.RECTIFYING, 1, "order-1",
                new FiscalTicket.Correction(sale.id(), sale.fullNumber(), sale.issuedOn(), "Wrong dish"));
        in(tenant, () -> tickets.add(refund));

        assertThat(in(tenant, () -> tickets.findCorrection(sale.id(), TicketKind.RECTIFYING)))
                .map(FiscalTicket::fullNumber).hasValue(refund.fullNumber());
        assertThat(in(tenant, () -> tickets.findCorrection(sale.id(), TicketKind.REPLACEMENT))).isEmpty();
        // A refund is not a sale: the order still points at the ticket that charged it
        assertThat(in(tenant, () -> tickets.findSaleByOrderId("order-1"))).map(FiscalTicket::id).hasValue(sale.id());
    }

    @Test
    void aCancelledTicketStaysAndFreesItsOrdersToBeChargedAgain() {
        String tenant = tenant();
        var sale = in(tenant, () -> tickets.add(ticket(TicketKind.SIMPLIFIED, 1, "order-1", null)));
        var cancellation = new es.brasatech.fastbite.domain.fiscal.TicketCancellation(sale.id(),
                OffsetDateTime.parse("2026-10-09T23:40:00+02:00"), "maria", "Wrong table");

        in(tenant, () -> { tickets.cancel(cancellation); return null; });

        assertThat(in(tenant, () -> tickets.findCancellation(sale.id()))).hasValue(cancellation);
        assertThat(in(tenant, () -> tickets.findById(sale.id()))).as("the ticket itself never changes").isPresent();
        assertThat(in(tenant, () -> tickets.findSaleByOrderId("order-1"))).isEmpty();
        var again = in(tenant, () -> tickets.add(ticket(TicketKind.SIMPLIFIED, 2, "order-1", null)));
        assertThat(in(tenant, () -> tickets.findSaleByOrderId("order-1"))).map(FiscalTicket::id).hasValue(again.id());
        assertThatThrownBy(() -> in(tenant, () -> { tickets.cancel(cancellation); return null; }))
                .as("cancelled once only").isInstanceOf(RuntimeException.class);
    }
}
