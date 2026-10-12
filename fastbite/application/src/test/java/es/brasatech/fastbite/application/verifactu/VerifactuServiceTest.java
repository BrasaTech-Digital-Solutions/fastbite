package es.brasatech.fastbite.application.verifactu;

import es.brasatech.fastbite.application.fiscal.FiscalTicketRepository;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.PaymentMethod;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.fiscal.VatBreakdown;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.verifactu.Verifactu;
import es.brasatech.fastbite.domain.verifactu.VerifactuRecord;
import es.brasatech.fastbite.domain.verifactu.VerifactuStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerifactuServiceTest {

    /** Records kept in memory. */
    static class MemoryRecords implements VerifactuRepository {
        final List<VerifactuRecord> all = new ArrayList<>();

        public Optional<VerifactuRecord> lockChain(String chain) { return findLast(chain); }
        public VerifactuRecord append(VerifactuRecord record) { all.add(record); return record; }
        public Optional<VerifactuRecord> findById(String id) {
            return all.stream().filter(r -> r.id().equals(id)).findFirst();
        }
        public Optional<VerifactuRecord> findByTicketId(String ticketId) {
            return all.stream().filter(r -> r.ticketId().equals(ticketId)).max(Comparator.comparingInt(VerifactuRecord::sequence));
        }
        public Optional<VerifactuRecord> findInChain(String chain, int sequence) {
            return all.stream().filter(r -> r.chain().equals(chain) && r.sequence() == sequence).findFirst();
        }
        public Optional<VerifactuRecord> findLast(String chain) {
            return all.stream().filter(r -> r.chain().equals(chain)).max(Comparator.comparingInt(VerifactuRecord::sequence));
        }
        public List<VerifactuRecord> findPending(String chain, int limit) {
            return all.stream().filter(r -> r.chain().equals(chain) && r.submission().status() == VerifactuStatus.PENDING)
                    .limit(limit).toList();
        }
        public List<VerifactuRecord> findToAmend(String chain, int limit) {
            return all.stream().filter(r -> r.chain().equals(chain) && r.amendable()).toList();
        }
        public List<VerifactuRecord> findAll(String chain) { return all.stream().filter(r -> r.chain().equals(chain)).toList(); }
        public void updateSubmission(String id, VerifactuRecord.Submission submission) {
            all.replaceAll(r -> r.id().equals(id) ? r.withSubmission(submission) : r);
        }
        final Map<String, VerifactuSubmitter.Installation> installations = new java.util.HashMap<>();
        public VerifactuSubmitter.Installation installation(String chain, String number, boolean multiple) {
            var known = installations.computeIfAbsent(chain, c -> new VerifactuSubmitter.Installation(number, multiple));
            if (multiple && !known.multipleBillings()) {
                known = new VerifactuSubmitter.Installation(known.number(), true);
                installations.put(chain, known);
            }
            return known;
        }
        public Map<VerifactuStatus, Long> countByStatus(String chain) {
            return all.stream().filter(r -> r.chain().equals(chain))
                    .collect(Collectors.groupingBy(r -> r.submission().status(), Collectors.counting()));
        }
    }

    /** A submitter that accepts everything except the invoice numbers it is told to refuse. */
    static class FakeSubmitter implements VerifactuSubmitter {
        boolean configured = true;
        final List<List<Submission>> calls = new ArrayList<>();
        final List<Installation> installations = new ArrayList<>();
        public boolean configured() { return configured; }
        public String name() { return "Test provider"; }
        public List<Result> submit(VerifactuSystem system, Installation installation, List<Submission> records,
                boolean production) {
            calls.add(records);
            installations.add(installation);
            return records.stream().map(s -> s.record().invoiceNumber().endsWith("2")
                    ? new Result(s.record().id(), VerifactuStatus.REJECTED, "Invalid NIF", null)
                    : new Result(s.record().id(), VerifactuStatus.ACCEPTED, null, "CSV" + s.record().sequence())).toList();
        }
    }

    static final Clock NOON = Clock.fixed(Instant.parse("2026-10-09T11:00:05.789Z"), ZoneId.of("Europe/Madrid"));
    static final VerifactuSystem SYSTEM = new VerifactuSystem("Brasatech", "B12345674", "FastBite", "FB", "1.0",
            "Servicios de restauración");

    MemoryRecords records = new MemoryRecords();
    FakeSubmitter submitter = new FakeSubmitter();
    FiscalTicketRepository tickets = mock(FiscalTicketRepository.class);
    VerifactuService service = new VerifactuService(records, tickets, submitter, SYSTEM, NOON);

    private FiscalTicket ticket(TicketKind kind, int number, boolean training, FiscalTicket.Correction corrects) {
        String series = (training ? "TEST-" : "") + "KH-" + kind.seriesLetter() + "26";
        BigDecimal total = kind == TicketKind.RECTIFYING ? new BigDecimal("-11.00") : new BigDecimal("11.00");
        var ticket = new FiscalTicket("ticket-" + series + number, kind, series, number, series + "-00000" + number,
                training, OffsetDateTime.parse("2026-10-09T13:00:00+02:00"), "carla",
                new FiscalTicket.Issuer("Ana García", null, "12345678Z", "Calle Mayor 1", "28013", "Madrid", "Madrid", null),
                null, List.of(), BigDecimal.ZERO, total,
                VatBreakdown.of(List.of(new VatBreakdown.Amount(total, new BigDecimal("10")))),
                BigDecimal.ZERO, PaymentMethod.CARD, null, null, corrects, List.of("o" + number), null, null, null);
        when(tickets.findById(ticket.id())).thenReturn(Optional.of(ticket));
        return ticket;
    }

    @BeforeEach
    void setUp() {
        when(tickets.findById(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void eachTicketGetsARecordChainedToThePreviousOne() {
        var first = service.record(ticket(TicketKind.SIMPLIFIED, 1, false, null));
        var second = service.record(ticket(TicketKind.FULL, 1, false, null));

        assertThat(first.sequence()).isEqualTo(1);
        assertThat(first.firstOfChain()).isTrue();
        assertThat(first.invoiceType()).isEqualTo("F2");
        assertThat(first.taxTotal()).isEqualByComparingTo("1.00");
        assertThat(first.generatedAt()).isEqualTo(OffsetDateTime.parse("2026-10-09T13:00:05+02:00"));
        assertThat(first.fingerprint()).isEqualTo(Verifactu.fingerprint("12345678Z", "KH-T26-000001",
                first.issueDate(), "F2", new BigDecimal("1.00"), new BigDecimal("11.00"), null, first.generatedAt()));
        assertThat(first.qrUrl()).startsWith(Verifactu.QR_PRODUCTION).contains("importe=11.00");
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(second.invoiceType()).isEqualTo("F1");
        assertThat(second.previous().fingerprint()).isEqualTo(first.fingerprint());
        assertThat(second.previous().invoiceNumber()).isEqualTo("KH-T26-000001");
    }

    @Test
    void correctionsNameTheInvoiceTheyCorrect() {
        var sale = ticket(TicketKind.SIMPLIFIED, 1, false, null);
        service.record(sale);

        var refund = service.record(ticket(TicketKind.RECTIFYING, 1, false,
                new FiscalTicket.Correction(sale.id(), sale.fullNumber(), sale.issuedOn(), "Wrong dish")));

        assertThat(refund.invoiceType()).isEqualTo("R5");
        assertThat(refund.amountTotal()).isEqualByComparingTo("-11.00");
        assertThat(refund.corrected().invoiceNumber()).isEqualTo(sale.fullNumber());
    }

    @Test
    void trainingTicketsHaveTheirOwnChainAndPointToTheTestEnvironment() {
        service.record(ticket(TicketKind.SIMPLIFIED, 1, false, null));
        var training = service.record(ticket(TicketKind.SIMPLIFIED, 1, true, null));

        assertThat(training.chain()).isEqualTo(VerifactuRecord.TRAINING_CHAIN);
        assertThat(training.sequence()).isEqualTo(1);
        assertThat(training.firstOfChain()).isTrue();
        assertThat(training.qrUrl()).startsWith(Verifactu.QR_TEST);
    }

    @Test
    void pendingRecordsAreSentInChainOrderAndTheAnswersKept() {
        service.record(ticket(TicketKind.SIMPLIFIED, 1, false, null));
        service.record(ticket(TicketKind.SIMPLIFIED, 2, false, null));

        assertThat(service.submitPending(false, false)).isEqualTo(2);

        assertThat(submitter.calls).singleElement().satisfies(batch ->
                assertThat(batch).extracting(s -> s.record().sequence()).containsExactly(1, 2));
        var summary = service.summary(false);
        assertThat(summary.count(VerifactuStatus.ACCEPTED)).isEqualTo(1);
        assertThat(summary.count(VerifactuStatus.REJECTED)).isEqualTo(1);
        assertThat(summary.toAmend()).singleElement().satisfies(rejected ->
                assertThat(rejected.submission().error()).isEqualTo("Invalid NIF"));
        assertThat(records.all.getFirst().submission().csv()).isEqualTo("CSV1");
        assertThat(service.submitPending(false, false)).as("nothing left to send").isZero();
    }

    @Test
    void withoutAProviderRecordsStayPendingAndNothingIsSent() {
        submitter.configured = false;
        service.record(ticket(TicketKind.SIMPLIFIED, 1, false, null));

        assertThat(service.submitPending(false, false)).isZero();
        assertThat(submitter.calls).isEmpty();
        assertThat(service.summary(false).count(VerifactuStatus.PENDING)).isEqualTo(1);
        assertThat(service.summary(false).configured()).isFalse();
    }

    @Test
    void aRefusedRecordIsAmendedByANewRecordAtTheEndOfTheChain() {
        service.record(ticket(TicketKind.SIMPLIFIED, 1, false, null));
        var refused = service.record(ticket(TicketKind.SIMPLIFIED, 2, false, null));
        service.record(ticket(TicketKind.SIMPLIFIED, 3, false, null));
        service.submitPending(false, false);

        var amendment = service.amend(refused.id(), "Ana García López");

        var third = records.all.get(2);
        assertThat(amendment.sequence()).isEqualTo(4);
        assertThat(amendment.invoiceNumber()).isEqualTo(refused.invoiceNumber());
        assertThat(amendment.ticketId()).isEqualTo(refused.ticketId());
        assertThat(amendment.issuerName()).isEqualTo("Ana García López");
        assertThat(amendment.amends()).isEqualTo(new VerifactuRecord.Amendment(refused.id(), true));
        assertThat(amendment.previous().fingerprint()).as("chained to the last record, not the amended one")
                .isEqualTo(third.fingerprint());
        assertThat(amendment.fingerprint()).isEqualTo(Verifactu.fingerprint("12345678Z", refused.invoiceNumber(),
                refused.issueDate(), "F2", refused.taxTotal(), refused.amountTotal(), third.fingerprint(),
                amendment.generatedAt()));
        assertThat(amendment.submission().status()).isEqualTo(VerifactuStatus.PENDING);
        var amended = records.findById(refused.id()).orElseThrow();
        assertThat(amended.submission().status()).isEqualTo(VerifactuStatus.AMENDED);
        assertThat(amended.fingerprint()).as("the amended record never changes").isEqualTo(refused.fingerprint());
        assertThat(service.summary(false).toAmend()).isEmpty();
        assertThat(service.findForTicket(refused.ticketId())).hasValue(amendment);
    }

    @Test
    void anInvoiceTheAgencyAcceptedOnceIsNeverAmendedAsRefusedBefore() {
        var record = service.record(ticket(TicketKind.SIMPLIFIED, 1, false, null));
        records.updateSubmission(record.id(), new VerifactuRecord.Submission(VerifactuStatus.ACCEPTED_WITH_ERRORS, 1,
                "2000: Aviso", "CSV1", OffsetDateTime.now(NOON)));

        var first = service.amend(record.id(), null);
        assertThat(first.amends().afterRejection()).isFalse();
        assertThat(first.issuerName()).as("kept when no new name is given").isEqualTo("Ana García");

        records.updateSubmission(first.id(), new VerifactuRecord.Submission(VerifactuStatus.REJECTED, 1, "1100: Mal",
                null, OffsetDateTime.now(NOON)));
        var second = service.amend(first.id(), null);
        assertThat(second.amends()).isEqualTo(new VerifactuRecord.Amendment(first.id(), false));
    }

    @Test
    void onlyRefusedRecordsOrRecordsWithErrorsCanBeAmended() {
        var pending = service.record(ticket(TicketKind.SIMPLIFIED, 1, false, null));

        assertThatThrownBy(() -> service.amend(pending.id(), "Ana")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.amend("missing", "Ana")).isInstanceOf(IllegalArgumentException.class);
        assertThat(records.all).hasSize(1);
    }

    @Test
    void eachChainIsOneInstallationWhoseNumberNeverChanges() {
        TenantContext.setCurrentTenant("kebab");
        try {
            service.record(ticket(TicketKind.SIMPLIFIED, 1, false, null));
            service.submitPending(false, false);
            service.record(ticket(TicketKind.SIMPLIFIED, 3, false, null));
            service.submitPending(false, true);
            service.record(ticket(TicketKind.SIMPLIFIED, 5, false, null));
            service.submitPending(false, false);
        } finally {
            TenantContext.clear();
        }

        assertThat(submitter.installations).extracting(VerifactuSubmitter.Installation::number)
                .containsOnly("kebab-R-20261009110005");
        assertThat(submitter.installations).extracting(VerifactuSubmitter.Installation::multipleBillings)
                .as("once the owner has had two locations, always").containsExactly(false, true, true);
        assertThat(VerifactuService.installationNumber(VerifactuRecord.TRAINING_CHAIN, OffsetDateTime.now(NOON)))
                .as("the sandbox chain is another installation").endsWith("-T-20261009110005");
    }

    @Test
    void aBrokenChainIsReportedButBillingGoesOn() {
        service.record(ticket(TicketKind.SIMPLIFIED, 1, false, null));
        var second = service.record(ticket(TicketKind.SIMPLIFIED, 2, false, null));
        assertThat(service.summary(false).chainIntact()).isTrue();
        // Someone changed the amount of the last record after it was hashed
        records.all.set(1, new VerifactuRecord(second.id(), second.ticketId(), second.chain(), 2, "F2",
                second.issuerNif(), second.issuerName(), second.invoiceNumber(), second.issueDate(), second.generatedAt(),
                second.taxTotal(), new BigDecimal("1.00"), second.previous(), second.fingerprint(), second.qrUrl(), null,
                null, false, second.submission()));

        assertThat(service.summary(false).chainIntact()).isFalse();
        var third = service.record(ticket(TicketKind.SIMPLIFIED, 3, false, null));

        assertThat(third.previous().fingerprint()).as("chained to the last record as it is")
                .isEqualTo(second.fingerprint());
        assertThat(service.summary(false).chainIntact()).as("the new end of the chain is sound").isTrue();
    }

    @Test
    void aClockBehindTheLastRecordNeverPutsRecordsOutOfOrder() {
        var first = service.record(ticket(TicketKind.SIMPLIFIED, 1, false, null));
        Clock behind = Clock.offset(NOON, java.time.Duration.ofMinutes(-5));
        var late = new VerifactuService(records, tickets, submitter, SYSTEM, behind);

        var second = late.record(ticket(TicketKind.SIMPLIFIED, 2, false, null));

        assertThat(second.generatedAt()).isEqualTo(first.generatedAt());
        assertThat(service.summary(false).chainIntact()).isTrue();
    }
}
