package es.brasatech.fastbite.application.fiscal;

import java.time.Instant;
import java.util.Optional;

public interface FiscalIssuerPort {

    /**
     * Records a valid Spanish tax ID (NIF/NIE/CIF) for a location in production,
     * if not already present. Insert-only (never update or delete).
     *
     * @return true if a new record was inserted; false if already recorded
     */
    boolean recordIfAbsent(String taxId, String tenantId, Instant seenAt);

    /**
     * Checks if this owner has previously used this tax ID on another location,
     * returning the earliest date it was first seen.
     * Never reveals anything about other owners.
     */
    Optional<Instant> findPriorUsageByOwner(String ownerUsername, String taxId, String currentTenantId);
}
