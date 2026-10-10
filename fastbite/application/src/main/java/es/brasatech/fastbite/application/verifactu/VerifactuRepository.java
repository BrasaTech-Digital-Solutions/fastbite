package es.brasatech.fastbite.application.verifactu;

import es.brasatech.fastbite.domain.verifactu.VerifactuRecord;
import es.brasatech.fastbite.domain.verifactu.VerifactuStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The billing records of the restaurant the current request is for. */
public interface VerifactuRepository {

    /**
     * Locks a chain until the surrounding transaction ends and returns its last record, so the
     * next record is chained to it and no other record slips in between.
     */
    Optional<VerifactuRecord> lockChain(String chain);

    /** Adds a record at the end of its chain; the chain must be locked by this transaction. */
    VerifactuRecord append(VerifactuRecord record);

    Optional<VerifactuRecord> findByTicketId(String ticketId);

    /** The last record of a chain, without locking it. */
    Optional<VerifactuRecord> findLast(String chain);

    /** Records not yet accepted, oldest first: the tax agency wants them in chain order. */
    List<VerifactuRecord> findPending(String chain, int limit);

    /** Records whose submission was refused and need attention, newest first. */
    List<VerifactuRecord> findRejected(String chain, int limit);

    /** Every record of a chain in order, for the restaurant to keep or show an inspector. */
    List<VerifactuRecord> findAll(String chain);

    void updateSubmission(String recordId, VerifactuRecord.Submission submission);

    Map<VerifactuStatus, Long> countByStatus(String chain);
}
