package es.brasatech.fastbite.application.verifactu;

import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.verifactu.VerifactuRecord;
import es.brasatech.fastbite.domain.verifactu.VerifactuStatus;

import java.util.List;

/**
 * Sends billing records to the tax agency, directly or through a certified provider. FastBite
 * connects directly (module verifactu-aeat); until it is configured, records wait as pending.
 */
public interface VerifactuSubmitter {

    /** A record with the invoice it describes, as a provider needs both. */
    record Submission(VerifactuRecord record, FiscalTicket ticket) {
    }

    /**
     * The billing system as the tax agency tells installations apart: each location's chain is
     * its own installation, because the agency keeps one chain per NIF, system and installation.
     *
     * @param number           NumeroInstalacion: never reused for the same NIF
     * @param multipleBillings IndicadorMultiplesOT: the owner has, or has had, more than one location
     */
    record Installation(String number, boolean multipleBillings) {
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
     * @param installation the installation the records were generated in
     * @param production false in the sandbox, which only ever talks to the tax agency's test environment
     * @return one result per record that got an answer; records without one stay pending
     */
    List<Result> submit(VerifactuSystem system, Installation installation, List<Submission> records, boolean production);
}
