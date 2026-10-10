package es.brasatech.fastbite.application.verifactu;

import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.verifactu.VerifactuRecord;
import es.brasatech.fastbite.domain.verifactu.VerifactuStatus;

import java.util.List;

/**
 * Sends billing records to the tax agency, directly or through a certified provider. Which one
 * is still to be decided; until an adapter is configured, records wait as pending.
 */
public interface VerifactuSubmitter {

    /** A record with the invoice it describes, as a provider needs both. */
    record Submission(VerifactuRecord record, FiscalTicket ticket) {
    }

    /** What the tax agency answered for one record. */
    record Result(String recordId, VerifactuStatus status, String error, String csv) {
    }

    /** Whether records can be sent at all. */
    boolean configured();

    /** A short name for the owner console, such as the provider's name. */
    String name();

    /**
     * Sends records of one location, in chain order.
     *
     * @param production false in the sandbox, which only ever talks to the tax agency's test environment
     * @return one result per record that got an answer; records without one stay pending
     */
    List<Result> submit(VerifactuSystem system, List<Submission> records, boolean production);
}
