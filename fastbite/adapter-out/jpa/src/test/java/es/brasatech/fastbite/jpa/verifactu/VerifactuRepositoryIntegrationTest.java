package es.brasatech.fastbite.jpa.verifactu;

import es.brasatech.fastbite.application.fiscal.FiscalTicketRepository;
import es.brasatech.fastbite.application.verifactu.VerifactuRepository;
import es.brasatech.fastbite.application.verifactu.VerifactuService;
import es.brasatech.fastbite.application.verifactu.VerifactuSubmitter;
import es.brasatech.fastbite.application.verifactu.VerifactuSystem;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.PaymentMethod;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.fiscal.VatBreakdown;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.verifactu.Verifactu;
import es.brasatech.fastbite.domain.verifactu.VerifactuRecord;
import es.brasatech.fastbite.domain.verifactu.VerifactuStatus;
import es.brasatech.fastbite.jpa.TestConfig;
import es.brasatech.fastbite.jpa.tenant.TenantProvisionerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TestConfig.class)
class VerifactuRepositoryIntegrationTest {

    @Autowired VerifactuRepository records;
    @Autowired FiscalTicketRepository tickets;
    @Autowired TenantProvisionerAdapter provisioner;
    @Autowired TransactionTemplate transactions;

    @AfterEach void clear() { TenantContext.clear(); }

    private String tenant() {
        String tenant = "records" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        provisioner.provisionTenant(tenant);
        return tenant;
    }

    private <T> T in(String tenant, Supplier<T> work) {
        TenantContext.setCurrentTenant(tenant);
        try { return transactions.execute(status -> work.get()); } finally { TenantContext.clear(); }
    }

    /** Issues a ticket and appends its record, as issuing a ticket does. */
    private VerifactuRecord issue(String chain) {
        String id = UUID.randomUUID().toString();
        String number = "KH-T26-" + id.substring(0, 6);
        tickets.add(new FiscalTicket(id, TicketKind.SIMPLIFIED, "KH-T26", 1, number, false,
                OffsetDateTime.parse("2026-10-09T13:00:00+02:00"), "carla",
                new FiscalTicket.Issuer("Ana", null, "12345678Z", null, null, null, null, null), null, List.of(),
                BigDecimal.ZERO, new BigDecimal("11.00"), VatBreakdown.EMPTY, BigDecimal.ZERO, PaymentMethod.CARD, null,
                null, null, List.of(id), null, null, null));
        var last = records.lockChain(chain);
        var previous = last.map(r -> new VerifactuRecord.Link(r.issuerNif(), r.invoiceNumber(), r.issueDate(), r.fingerprint()))
                .orElse(null);
        var generated = OffsetDateTime.parse("2026-10-09T13:00:01+02:00");
        String fingerprint = Verifactu.fingerprint("12345678Z", number, generated.toLocalDate(), "F2",
                new BigDecimal("1.00"), new BigDecimal("11.00"), previous == null ? null : previous.fingerprint(), generated);
        return records.append(new VerifactuRecord(UUID.randomUUID().toString(), id, chain,
                last.map(r -> r.sequence() + 1).orElse(1), "F2", "12345678Z", "Ana", number, generated.toLocalDate(),
                generated, new BigDecimal("1.00"), new BigDecimal("11.00"), previous, fingerprint, "https://qr", null,
                null, false, VerifactuRecord.Submission.PENDING));
    }

    @Test
    void recordsAreChainedInOrderAndReadBackAsWritten() {
        String tenant = tenant();
        var first = in(tenant, () -> issue(VerifactuRecord.REAL_CHAIN));
        var second = in(tenant, () -> issue(VerifactuRecord.REAL_CHAIN));

        assertThat(second.sequence()).isEqualTo(2);
        assertThat(second.previous().fingerprint()).isEqualTo(first.fingerprint());
        assertThat(in(tenant, () -> records.findByTicketId(second.ticketId()))).hasValue(second);
        assertThat(in(tenant, () -> records.findLast(VerifactuRecord.REAL_CHAIN))).hasValue(second);
        assertThat(in(tenant, () -> records.findAll(VerifactuRecord.TRAINING_CHAIN))).isEmpty();
    }

    @Test
    void aRecordThatDoesNotFollowTheEndOfTheChainIsRefused() {
        String tenant = tenant();
        var first = in(tenant, () -> issue(VerifactuRecord.REAL_CHAIN));

        assertThatThrownBy(() -> in(tenant, () -> {
            records.lockChain(VerifactuRecord.REAL_CHAIN);
            return records.append(new VerifactuRecord(UUID.randomUUID().toString(), first.ticketId(), first.chain(), 1,
                    "F2", "12345678Z", "Ana", "KH-T26-X", first.issueDate(), first.generatedAt(), BigDecimal.ONE,
                    BigDecimal.TEN, null, "F", "https://qr", null, null, false, VerifactuRecord.Submission.PENDING));
        })).isInstanceOf(RuntimeException.class);
    }

    @Test
    void concurrentTicketsStillFormOneUnbrokenChain() throws Exception {
        String tenant = tenant();
        Callable<VerifactuRecord> issueOne = () -> in(tenant, () -> issue(VerifactuRecord.REAL_CHAIN));
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (var future : pool.invokeAll(Collections.nCopies(10, issueOne))) future.get();
        }

        List<VerifactuRecord> chain = in(tenant, () -> records.findAll(VerifactuRecord.REAL_CHAIN));
        assertThat(chain).extracting(VerifactuRecord::sequence).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        for (int i = 1; i < chain.size(); i++) {
            assertThat(chain.get(i).previous().fingerprint()).isEqualTo(chain.get(i - 1).fingerprint());
        }
    }

    @Test
    void submissionAnswersAreKeptAndPendingRecordsComeOldestFirst() {
        String tenant = tenant();
        var first = in(tenant, () -> issue(VerifactuRecord.REAL_CHAIN));
        var second = in(tenant, () -> issue(VerifactuRecord.REAL_CHAIN));
        in(tenant, () -> issue(VerifactuRecord.REAL_CHAIN));

        in(tenant, () -> {
            records.updateSubmission(first.id(), new VerifactuRecord.Submission(VerifactuStatus.ACCEPTED, 1, null, "CSV1",
                    OffsetDateTime.parse("2026-10-09T13:05:00+02:00")));
            records.updateSubmission(second.id(), new VerifactuRecord.Submission(VerifactuStatus.REJECTED, 1,
                    "NIF not identified", null, OffsetDateTime.parse("2026-10-09T13:05:00+02:00")));
            return null;
        });

        assertThat(in(tenant, () -> records.findPending(VerifactuRecord.REAL_CHAIN, 10))).extracting(VerifactuRecord::sequence)
                .containsExactly(3);
        assertThat(in(tenant, () -> records.findToAmend(VerifactuRecord.REAL_CHAIN, 10))).singleElement()
                .satisfies(r -> assertThat(r.submission().error()).isEqualTo("NIF not identified"));
        assertThat(in(tenant, () -> records.countByStatus(VerifactuRecord.REAL_CHAIN)))
                .containsEntry(VerifactuStatus.ACCEPTED, 1L).containsEntry(VerifactuStatus.REJECTED, 1L)
                .containsEntry(VerifactuStatus.PENDING, 1L);
        var accepted = in(tenant, () -> records.findByTicketId(first.ticketId())).orElseThrow();
        assertThat(accepted.submission().csv()).isEqualTo("CSV1");
        assertThat(accepted.fingerprint()).as("the fiscal content never changes").isEqualTo(first.fingerprint());
    }

    @Test
    void anAmendmentIsAnotherRecordOfTheSameTicket() {
        String tenant = tenant();
        var original = in(tenant, () -> issue(VerifactuRecord.REAL_CHAIN));
        var amendment = in(tenant, () -> {
            var last = records.lockChain(VerifactuRecord.REAL_CHAIN).orElseThrow();
            return records.append(new VerifactuRecord(UUID.randomUUID().toString(), original.ticketId(), original.chain(), 2,
                    "F2", "12345678Z", "Ana García", original.invoiceNumber(), original.issueDate(),
                    original.generatedAt().plusMinutes(5), original.taxTotal(), original.amountTotal(),
                    new VerifactuRecord.Link(last.issuerNif(), last.invoiceNumber(), last.issueDate(), last.fingerprint()),
                    "AMENDED-FINGERPRINT", original.qrUrl(), null, new VerifactuRecord.Amendment(original.id(), true), false,
                    VerifactuRecord.Submission.PENDING));
        });

        assertThat(in(tenant, () -> records.findById(amendment.id()))).hasValue(amendment);
        assertThat(in(tenant, () -> records.findByTicketId(original.ticketId()))).as("the latest record of the invoice")
                .hasValue(amendment);
        assertThat(in(tenant, () -> records.findById(original.id()))).hasValue(original);
        assertThatThrownBy(() -> in(tenant, () -> {
            records.lockChain(VerifactuRecord.REAL_CHAIN);
            return records.append(new VerifactuRecord(UUID.randomUUID().toString(), original.ticketId(), original.chain(), 3,
                    "F2", "12345678Z", "Ana", original.invoiceNumber(), original.issueDate(), original.generatedAt(),
                    original.taxTotal(), original.amountTotal(), null, "TWICE", original.qrUrl(), null,
                    new VerifactuRecord.Amendment(original.id(), true), false, VerifactuRecord.Submission.PENDING));
        })).as("a record is amended at most once").isInstanceOf(RuntimeException.class);
    }

    @Test
    void aChainKeepsItsFirstInstallationNumber() {
        String tenant = tenant();
        var first = in(tenant, () -> records.installation(VerifactuRecord.REAL_CHAIN, "loc-R-20261009110000", false));
        var later = in(tenant, () -> records.installation(VerifactuRecord.REAL_CHAIN, "loc-R-20261010120000", true));
        var after = in(tenant, () -> records.installation(VerifactuRecord.REAL_CHAIN, "loc-R-20261011130000", false));
        var training = in(tenant, () -> records.installation(VerifactuRecord.TRAINING_CHAIN, "loc-T-20261011130000", false));

        assertThat(first).isEqualTo(new VerifactuSubmitter.Installation("loc-R-20261009110000", false));
        assertThat(later).isEqualTo(new VerifactuSubmitter.Installation("loc-R-20261009110000", true));
        assertThat(after.multipleBillings()).as("stays true").isTrue();
        assertThat(training.number()).isEqualTo("loc-T-20261011130000");
    }

    @Test
    void concurrentSubmitPendingCallsSendEachRecordExactlyOnceWithNoActiveTransaction() throws Exception {
        String tenant = tenant();
        var first = in(tenant, () -> issue(VerifactuRecord.REAL_CHAIN));
        var second = in(tenant, () -> issue(VerifactuRecord.REAL_CHAIN));

        var submittedIds = new CopyOnWriteArrayList<String>();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var oneFinished = new CountDownLatch(1);

        var submitter = new VerifactuSubmitter() {
            @Override public boolean configured() { return true; }
            @Override public String name() { return "Test AEAT"; }
            @Override
            public List<Result> submit(VerifactuSystem system, Installation installation, List<Submission> batch,
                    boolean production) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                try {
                    oneFinished.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (Submission submission : batch) {
                    submittedIds.add(submission.record().id());
                }
                return batch.stream()
                        .map(s -> new Result(s.record().id(), VerifactuStatus.ACCEPTED, null, "CSV-" + s.record().sequence()))
                        .toList();
            }
        };

        var system = new VerifactuSystem("Brasatech", "B12345674", "FastBite", "FB", "1.0", "Restauración");
        var clock = Clock.fixed(Instant.parse("2026-10-09T11:05:00Z"), ZoneOffset.UTC);
        var service = new VerifactuService(records, tickets, submitter, system, transactions, clock);

        Callable<Integer> callSubmit = () -> {
            TenantContext.setCurrentTenant(tenant);
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return service.submitPending(false, false);
            } finally {
                oneFinished.countDown();
                TenantContext.clear();
            }
        };

        try (var pool = Executors.newFixedThreadPool(2)) {
            var f1 = pool.submit(callSubmit);
            var f2 = pool.submit(callSubmit);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int r1 = f1.get(10, TimeUnit.SECONDS);
            int r2 = f2.get(10, TimeUnit.SECONDS);
            assertThat(List.of(r1, r2)).containsExactlyInAnyOrder(2, 0);
        }

        assertThat(submittedIds).containsExactly(first.id(), second.id());
        assertThat(in(tenant, () -> records.countByStatus(VerifactuRecord.REAL_CHAIN)))
                .containsEntry(VerifactuStatus.ACCEPTED, 2L)
                .doesNotContainKey(VerifactuStatus.PENDING);
    }

    @Test
    void expiredLeaseIsTakenOverAndReleaseOnlyClearsOwnLease() {
        String tenant = tenant();
        Instant firstClaim = Instant.parse("2026-10-09T11:00:00Z");
        Instant beforeExpiry = firstClaim.plus(VerifactuRepository.CLAIM_LEASE).minusSeconds(1);
        Instant afterExpiry = firstClaim.plus(VerifactuRepository.CLAIM_LEASE).plusSeconds(1);

        assertThat(in(tenant, () -> records.claimSubmission(VerifactuRecord.REAL_CHAIN, firstClaim))).isTrue();
        assertThat(in(tenant, () -> records.holdsSubmissionLease(VerifactuRecord.REAL_CHAIN, firstClaim))).isTrue();
        assertThat(in(tenant, () -> records.claimSubmission(VerifactuRecord.REAL_CHAIN, beforeExpiry))).isFalse();

        // Take over the expired lease
        assertThat(in(tenant, () -> records.claimSubmission(VerifactuRecord.REAL_CHAIN, afterExpiry))).isTrue();
        assertThat(in(tenant, () -> records.holdsSubmissionLease(VerifactuRecord.REAL_CHAIN, firstClaim))).isFalse();
        assertThat(in(tenant, () -> records.holdsSubmissionLease(VerifactuRecord.REAL_CHAIN, afterExpiry))).isTrue();

        // The previous holder releasing late must not release the new holder's lease
        in(tenant, () -> {
            records.releaseSubmission(VerifactuRecord.REAL_CHAIN, firstClaim);
            return null;
        });
        assertThat(in(tenant, () -> records.holdsSubmissionLease(VerifactuRecord.REAL_CHAIN, afterExpiry))).isTrue();

        // The new holder releases its own lease
        in(tenant, () -> {
            records.releaseSubmission(VerifactuRecord.REAL_CHAIN, afterExpiry);
            return null;
        });
        assertThat(in(tenant, () -> records.holdsSubmissionLease(VerifactuRecord.REAL_CHAIN, afterExpiry))).isFalse();
    }

    @Test
    void failedSubmissionReleasesLeaseSoNextRunRetries() {
        String tenant = tenant();
        var record = in(tenant, () -> issue(VerifactuRecord.REAL_CHAIN));
        var failFirst = new AtomicBoolean(true);

        var submitter = new VerifactuSubmitter() {
            @Override public boolean configured() { return true; }
            @Override public String name() { return "Test AEAT"; }
            @Override
            public List<Result> submit(VerifactuSystem system, Installation installation, List<Submission> batch,
                    boolean production) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                if (failFirst.getAndSet(false)) {
                    throw new IllegalStateException("Simulated AEAT failure");
                }
                return batch.stream()
                        .map(s -> new Result(s.record().id(), VerifactuStatus.ACCEPTED, null, "CSV-RETRY"))
                        .toList();
            }
        };

        var system = new VerifactuSystem("Brasatech", "B12345674", "FastBite", "FB", "1.0", "Restauración");
        var clock = Clock.fixed(Instant.parse("2026-10-09T11:05:00Z"), ZoneOffset.UTC);
        var service = new VerifactuService(records, tickets, submitter, system, transactions, clock);

        TenantContext.setCurrentTenant(tenant);
        try {
            assertThatThrownBy(() -> service.submitPending(false, false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Simulated AEAT failure");

            assertThat(service.submitPending(false, false)).isEqualTo(1);
        } finally {
            TenantContext.clear();
        }

        assertThat(in(tenant, () -> records.findById(record.id())).orElseThrow().submission().csv())
                .isEqualTo("CSV-RETRY");
    }
}
