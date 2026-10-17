package es.brasatech.fastbite.application.verifactu;

import es.brasatech.fastbite.domain.verifactu.VerifactuRecord;
import es.brasatech.fastbite.domain.verifactu.VerifactuStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The billing records of the restaurant the current request is for. */
public interface VerifactuRepository {

    /**
     * How long a submitter holds a chain while sending its pending records to the AEAT. Must exceed
     * {@code AeatVerifactuSubmitter}'s 30s connect timeout plus 120s request timeout (150s total) so a
     * slow AEAT response never triggers a duplicate submission mid-flight.
     */
    Duration CLAIM_LEASE = Duration.ofMinutes(5);

    /**
     * Locks a chain until the surrounding transaction ends and returns its last record, so the
     * next record is chained to it and no other record slips in between.
     */
    Optional<VerifactuRecord> lockChain(String chain);

    /** Adds a record at the end of its chain; the chain must be locked by this transaction. */
    VerifactuRecord append(VerifactuRecord record);

    Optional<VerifactuRecord> findById(String recordId);

    /** The latest record of a ticket's invoice: its first record, or the last amendment of it. */
    Optional<VerifactuRecord> findByTicketId(String ticketId);

    /** The record at a position of a chain, counting from 1. */
    Optional<VerifactuRecord> findInChain(String chain, int sequence);

    /** The last record of a chain, without locking it. */
    Optional<VerifactuRecord> findLast(String chain);

    /** Records not yet accepted, oldest first: the tax agency wants them in chain order. */
    List<VerifactuRecord> findPending(String chain, int limit);

    /** Records refused or accepted with errors, not amended yet, newest first. */
    List<VerifactuRecord> findToAmend(String chain, int limit);

    /** Every record of a chain in order, for the restaurant to keep or show an inspector. */
    List<VerifactuRecord> findAll(String chain);

    void updateSubmission(String recordId, VerifactuRecord.Submission submission);

    Map<VerifactuStatus, Long> countByStatus(String chain);

    /**
     * The chain's installation. The first call creates it with the given number, which never
     * changes afterwards; once {@code multipleBillings} is true it stays true.
     */
    VerifactuSubmitter.Installation installation(String chain, String newNumber, boolean multipleBillings);

    /**
     * Atomically claims a chain for submission, taking over an expired lease if a previous
     * submitter crashed.
     *
     * @return true if claimed by this caller; false if another submitter holds an active lease
     */
    boolean claimSubmission(String chain, Instant claimedAt);

    /** Whether the chain is still claimed by the given lease timestamp. */
    boolean holdsSubmissionLease(String chain, Instant claimedAt);

    /** Releases the submission lease on a chain if and only if it is still held by {@code claimedAt}. */
    void releaseSubmission(String chain, Instant claimedAt);
}
