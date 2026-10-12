package es.brasatech.fastbite.application.verifactu;

import es.brasatech.fastbite.application.fiscal.FiscalTicketRepository;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.verifactu.Verifactu;
import es.brasatech.fastbite.domain.verifactu.VerifactuRecord;
import es.brasatech.fastbite.domain.verifactu.VerifactuStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Generates the VERI*FACTU billing record of every ticket, chained to the one before, and sends
 * pending records when a submitter is configured.
 */
@Service
@Transactional
public class VerifactuService {

    /** The tax agency takes at most this many records per submission. */
    static final int BATCH = 1000;

    private static final DateTimeFormatter INSTALLED = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Logger log = LoggerFactory.getLogger(VerifactuService.class);

    /**
     * Where a location's records stand, for the owner console.
     *
     * @param toAmend     records refused or accepted with errors, newest first, which can be sent again amended
     * @param chainIntact the last record matches its fingerprint and the record before it
     */
    public record Summary(boolean configured, String submitter, boolean training, Map<VerifactuStatus, Long> counts,
            VerifactuRecord last, List<VerifactuRecord> toAmend, boolean chainIntact) {

        public long total() {
            return counts.values().stream().mapToLong(Long::longValue).sum();
        }

        public long count(VerifactuStatus status) {
            return counts.getOrDefault(status, 0L);
        }

        public long pending() {
            return count(VerifactuStatus.PENDING);
        }

        public long accepted() {
            return count(VerifactuStatus.ACCEPTED) + count(VerifactuStatus.ACCEPTED_WITH_ERRORS);
        }

        public long refused() {
            return count(VerifactuStatus.REJECTED);
        }
    }

    private final VerifactuRepository records;
    private final FiscalTicketRepository tickets;
    private final VerifactuSubmitter submitter;
    private final VerifactuSystem system;
    private final Clock clock;

    @Autowired
    public VerifactuService(VerifactuRepository records, FiscalTicketRepository tickets, VerifactuSubmitter submitter,
            VerifactuSystem system) {
        this(records, tickets, submitter, system, Clock.systemDefaultZone());
    }

    public VerifactuService(VerifactuRepository records, FiscalTicketRepository tickets, VerifactuSubmitter submitter,
            VerifactuSystem system, Clock clock) {
        this.records = records;
        this.tickets = tickets;
        this.submitter = submitter;
        this.system = system;
        this.clock = clock;
    }

    /**
     * Generates the record of a ticket just issued, in the transaction that issues it. The record
     * is generated at the moment of issue, in the ticket's time zone.
     */
    public VerifactuRecord record(FiscalTicket ticket) {
        String chain = ticket.training() ? VerifactuRecord.TRAINING_CHAIN : VerifactuRecord.REAL_CHAIN;
        Optional<VerifactuRecord> last = records.lockChain(chain);
        checkChain(last);
        String nif = Objects.toString(ticket.issuer().taxId(), "");
        TicketKind correctedKind = null;
        VerifactuRecord.Link corrected = null;
        if (ticket.corrects() != null) {
            FiscalTicket original = tickets.findById(ticket.corrects().ticketId())
                    .orElseThrow(() -> new IllegalStateException("Corrected ticket " + ticket.corrects().fullNumber() + " not found"));
            correctedKind = original.kind();
            corrected = new VerifactuRecord.Link(Objects.toString(original.issuer().taxId(), ""), original.fullNumber(),
                    original.issuedOn(), null);
        }
        String type = Verifactu.invoiceType(ticket.kind(), correctedKind);
        // Generated now, to the second as it is hashed and sent, and never before the record it is chained to
        OffsetDateTime generatedAt = OffsetDateTime.now(clock.withZone(ticket.issuedAt().getOffset()))
                .truncatedTo(ChronoUnit.SECONDS);
        if (generatedAt.isBefore(ticket.issuedAt().truncatedTo(ChronoUnit.SECONDS))) {
            generatedAt = ticket.issuedAt().truncatedTo(ChronoUnit.SECONDS);
        }
        generatedAt = inOrder(generatedAt, last);
        VerifactuRecord.Link previous = last.map(record -> new VerifactuRecord.Link(record.issuerNif(),
                record.invoiceNumber(), record.issueDate(), record.fingerprint())).orElse(null);
        String fingerprint = Verifactu.fingerprint(nif, ticket.fullNumber(), ticket.issuedOn(), type,
                ticket.vat().vat(), ticket.total(), previous == null ? null : previous.fingerprint(), generatedAt);
        String qr = Verifactu.qrUrl(!ticket.training(), nif, ticket.fullNumber(), ticket.issuedOn(), ticket.total());
        return records.append(new VerifactuRecord(UUID.randomUUID().toString(), ticket.id(), chain,
                last.map(record -> record.sequence() + 1).orElse(1), type, nif, ticket.issuer().legalName(),
                ticket.fullNumber(), ticket.issuedOn(), generatedAt, ticket.vat().vat(), ticket.total(), previous,
                fingerprint, qr, corrected, null, ticket.training(), VerifactuRecord.Submission.PENDING));
    }

    /**
     * Amends a record the tax agency refused or accepted with errors (subsanación): a new record
     * of the same invoice, with the issuer's current legal name, appended to the end of the chain
     * with its own fingerprint. The amended record stays in the chain unchanged.
     *
     * <p>Only the record's data can be amended this way; a mistake in the invoice itself is
     * corrected with a corrective invoice.
     *
     * @param issuerName the location's legal name now, which may have been fixed since
     */
    public VerifactuRecord amend(String recordId, String issuerName) {
        VerifactuRecord original = records.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("Billing record " + recordId + " not found"));
        Optional<VerifactuRecord> last = records.lockChain(original.chain());
        checkChain(last);
        // Read again under the lock, so two clicks never amend the same record twice
        original = records.findById(recordId).orElseThrow();
        if (!original.amendable()) {
            throw new IllegalStateException("Only refused records and records accepted with errors can be amended");
        }
        // A refused cancellation is sent again with RechazoPrevio S; an invoice record with X while
        // the tax agency has never had the invoice
        boolean afterRejection = original.cancels() || neverAccepted(original);
        OffsetDateTime generatedAt = OffsetDateTime.now(clock.withZone(original.generatedAt().getOffset()))
                .truncatedTo(ChronoUnit.SECONDS);
        generatedAt = inOrder(generatedAt, last);
        VerifactuRecord.Link previous = last.map(record -> new VerifactuRecord.Link(record.issuerNif(),
                record.invoiceNumber(), record.issueDate(), record.fingerprint())).orElse(null);
        String previousFingerprint = previous == null ? null : previous.fingerprint();
        String fingerprint = original.cancels()
                ? Verifactu.cancellationFingerprint(original.issuerNif(), original.invoiceNumber(), original.issueDate(),
                        previousFingerprint, generatedAt)
                : Verifactu.fingerprint(original.issuerNif(), original.invoiceNumber(), original.issueDate(),
                        original.invoiceType(), original.taxTotal(), original.amountTotal(), previousFingerprint, generatedAt);
        String name = issuerName == null || issuerName.isBlank() ? original.issuerName() : issuerName.strip();
        VerifactuRecord amending = records.append(new VerifactuRecord(UUID.randomUUID().toString(), original.ticketId(),
                original.chain(), last.map(record -> record.sequence() + 1).orElse(1), original.invoiceType(),
                original.issuerNif(), name, original.invoiceNumber(), original.issueDate(), generatedAt,
                original.taxTotal(), original.amountTotal(), previous, fingerprint, original.qrUrl(), original.corrected(),
                new VerifactuRecord.Amendment(original.id(), afterRejection), original.cancellation(),
                original.training(), VerifactuRecord.Submission.PENDING));
        supersede(original);
        return amending;
    }

    /**
     * Generates the registro de anulación of a ticket cancelled because it was issued by mistake,
     * in the transaction that cancels it, at the end of the ticket's chain. When the tax agency
     * never had the invoice, it says so (SinRegistroPrevio S).
     */
    public VerifactuRecord cancel(FiscalTicket ticket) {
        String chain = ticket.training() ? VerifactuRecord.TRAINING_CHAIN : VerifactuRecord.REAL_CHAIN;
        Optional<VerifactuRecord> last = records.lockChain(chain);
        checkChain(last);
        Optional<VerifactuRecord> latest = records.findByTicketId(ticket.id());
        if (latest.isPresent() && latest.get().cancels()) {
            throw new IllegalStateException("Ticket " + ticket.fullNumber() + " already has a registro de anulación");
        }
        // No record at all: the ticket was issued before records were kept, so the agency never had it
        boolean withoutPreviousRecord = latest.map(VerifactuService::neverAccepted).orElse(true);
        String nif = Objects.toString(ticket.issuer().taxId(), "");
        OffsetDateTime generatedAt = inOrder(OffsetDateTime.now(clock.withZone(ticket.issuedAt().getOffset()))
                .truncatedTo(ChronoUnit.SECONDS), last);
        VerifactuRecord.Link previous = last.map(record -> new VerifactuRecord.Link(record.issuerNif(),
                record.invoiceNumber(), record.issueDate(), record.fingerprint())).orElse(null);
        String fingerprint = Verifactu.cancellationFingerprint(nif, ticket.fullNumber(), ticket.issuedOn(),
                previous == null ? null : previous.fingerprint(), generatedAt);
        VerifactuRecord cancellation = records.append(new VerifactuRecord(UUID.randomUUID().toString(), ticket.id(),
                chain, last.map(record -> record.sequence() + 1).orElse(1),
                latest.map(VerifactuRecord::invoiceType).orElse(Verifactu.invoiceType(ticket.kind(), null)), nif,
                latest.map(VerifactuRecord::issuerName).orElse(ticket.issuer().legalName()), ticket.fullNumber(),
                ticket.issuedOn(), generatedAt, ticket.vat().vat(), ticket.total(), previous, fingerprint,
                latest.map(VerifactuRecord::qrUrl).orElse(Verifactu.qrUrl(!ticket.training(), nif, ticket.fullNumber(),
                        ticket.issuedOn(), ticket.total())),
                null, null, new VerifactuRecord.Cancellation(withoutPreviousRecord), ticket.training(),
                VerifactuRecord.Submission.PENDING));
        // A record of the cancelled invoice still waiting to be amended no longer needs it
        latest.filter(VerifactuRecord::amendable).ifPresent(this::supersede);
        return cancellation;
    }

    /** The tax agency has never had the invoice of this record: it, and every record before it, was refused. */
    private static boolean neverAccepted(VerifactuRecord record) {
        return record.submission().status() == VerifactuStatus.REJECTED
                && (record.amends() == null || record.amends().afterRejection());
    }

    private void supersede(VerifactuRecord record) {
        VerifactuRecord.Submission was = record.submission();
        records.updateSubmission(record.id(), new VerifactuRecord.Submission(VerifactuStatus.AMENDED, was.attempts(),
                was.error(), was.csv(), was.at()));
    }

    @Transactional(readOnly = true)
    public Optional<VerifactuRecord> findForTicket(String ticketId) {
        return records.findByTicketId(ticketId);
    }

    @Transactional(readOnly = true)
    public Summary summary(boolean training) {
        String chain = training ? VerifactuRecord.TRAINING_CHAIN : VerifactuRecord.REAL_CHAIN;
        Map<VerifactuStatus, Long> counts = new EnumMap<>(VerifactuStatus.class);
        counts.putAll(records.countByStatus(chain));
        Optional<VerifactuRecord> last = records.findLast(chain);
        return new Summary(submitter.configured(), submitter.name(), training, counts, last.orElse(null),
                records.findToAmend(chain, 20), intact(last));
    }

    @Transactional(readOnly = true)
    public List<VerifactuRecord> chain(boolean training) {
        return records.findAll(training ? VerifactuRecord.TRAINING_CHAIN : VerifactuRecord.REAL_CHAIN);
    }

    /**
     * Sends the current location's pending records, oldest first, and stores the answers.
     *
     * @param multipleBillings the location's owner has more than one location
     * @return how many records got an answer
     */
    public int submitPending(boolean training, boolean multipleBillings) {
        if (!submitter.configured()) {
            return 0;
        }
        String chain = training ? VerifactuRecord.TRAINING_CHAIN : VerifactuRecord.REAL_CHAIN;
        List<VerifactuRecord> pending = records.findPending(chain, BATCH);
        if (pending.isEmpty()) {
            return 0;
        }
        List<VerifactuSubmitter.Submission> batch = new ArrayList<>();
        Set<String> invoices = new HashSet<>();
        for (VerifactuRecord record : pending) {
            // The agency answers per invoice: a second record of the same invoice, such as its
            // cancellation, waits for the next submission, so the chain order holds
            if (!invoices.add(record.issuerNif() + "|" + record.invoiceNumber())) {
                break;
            }
            tickets.findById(record.ticketId())
                    .ifPresent(ticket -> batch.add(new VerifactuSubmitter.Submission(record, ticket)));
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        VerifactuSubmitter.Installation installation = records.installation(chain, installationNumber(chain, now),
                multipleBillings);
        int answered = 0;
        for (VerifactuSubmitter.Result result : submitter.submit(system, installation, batch, !training)) {
            VerifactuRecord sent = pending.stream().filter(record -> record.id().equals(result.recordId())).findFirst()
                    .orElse(null);
            if (sent == null) {
                continue;
            }
            records.updateSubmission(sent.id(), new VerifactuRecord.Submission(result.status(),
                    sent.submission().attempts() + 1, result.error(), result.csv(), now));
            answered++;
        }
        return answered;
    }

    public boolean configured() {
        return submitter.configured();
    }

    /**
     * Checks, before a new record is generated, that the last one is correctly chained (Orden
     * HAC/1177/2024, art. 7.i.1). A broken chain never stops billing: it is reported, the owner
     * console shows it, and the new record is chained to the last one as it is.
     */
    private void checkChain(Optional<VerifactuRecord> last) {
        if (!intact(last)) {
            VerifactuRecord record = last.orElseThrow();
            log.error("VERI*FACTU: billing record {} ({}) of chain {} does not match its fingerprint or the record "
                    + "before it; the new record is chained to it as it is", record.sequence(), record.invoiceNumber(),
                    record.chain());
        }
    }

    private boolean intact(Optional<VerifactuRecord> last) {
        if (last.isEmpty()) {
            return true;
        }
        VerifactuRecord record = last.get();
        VerifactuRecord before = record.sequence() > 1
                ? records.findInChain(record.chain(), record.sequence() - 1).orElse(null) : null;
        return Verifactu.chained(record, before);
    }

    /**
     * Records are generated in the order their invoices are issued, so a record is never dated
     * before the one it follows (art. 7.i.2). A clock more than a minute behind the last record is
     * a clock problem: it is reported, and the record still keeps the order.
     */
    private static OffsetDateTime inOrder(OffsetDateTime generatedAt, Optional<VerifactuRecord> last) {
        if (last.isEmpty() || !generatedAt.isBefore(last.get().generatedAt())) {
            return generatedAt;
        }
        OffsetDateTime previous = last.get().generatedAt();
        if (generatedAt.isBefore(previous.minusMinutes(1))) {
            log.error("VERI*FACTU: the clock ({}) is more than a minute behind the last billing record of chain {} "
                    + "({}); the new record takes that record's time to keep the order", generatedAt,
                    last.get().chain(), previous);
        }
        return previous;
    }

    /**
     * A number no other installation of the same NIF has ever had: the location, its chain and
     * when the installation was set up, as the AEAT recommends (timestamp of the installation).
     */
    static String installationNumber(String chain, OffsetDateTime now) {
        String tenant = Objects.toString(TenantContext.getCurrentTenant(), "public");
        return tenant + "-" + (VerifactuRecord.TRAINING_CHAIN.equals(chain) ? "T" : "R") + "-"
                + now.atZoneSameInstant(ZoneOffset.UTC).format(INSTALLED);
    }
}
