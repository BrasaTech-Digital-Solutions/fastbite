package es.brasatech.fastbite.application.tenant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Tracks the single free-trial clock per Spanish NIF in production.
 * Records survive location deletion and owner account deletion.
 */
public interface NifTrialPort {

    record NifTrial(String nif, Instant trialStartedAt, Instant trialEndsAt) {
    }

    Optional<NifTrial> findByNif(String nif);

    /**
     * Returns the existing trial for this NIF, or creates one starting at {@code now}
     * and ending {@code trialDays} days later if this NIF has never started a trial.
     */
    NifTrial startOrGet(String nif, Instant now, int trialDays);

    /**
     * Extends an existing NIF trial's end date by {@code extraDays}.
     */
    Optional<NifTrial> extendByDays(String nif, int extraDays, Instant now);

    /**
     * Resolves the normalized NIF recorded for a tenant in {@code public.fiscal_issuers}.
     */
    Optional<String> findNifForTenant(String tenantId);

    /**
     * Lists all tenant IDs associated with a NIF in {@code public.fiscal_issuers}.
     */
    List<String> findTenantsForNif(String nif);
}
