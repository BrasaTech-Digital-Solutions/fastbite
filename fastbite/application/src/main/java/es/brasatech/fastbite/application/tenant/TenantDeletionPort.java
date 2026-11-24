package es.brasatech.fastbite.application.tenant;

/**
 * Port for inspecting fiscal/paid activity of a tenant location and completely removing a
 * location that has no legal retention obligations (sandbox locations or production locations
 * that have never issued a ticket or recorded a paid order).
 */
public interface TenantDeletionPort {

    /**
     * Returns {@code true} if the tenant schema contains any fiscal ticket, any VERI*FACTU
     * billing record, or any paid/refunded order.
     */
    boolean hasFiscalOrPaidActivity(String tenantId);

    /**
     * Permanently deletes the tenant mapping, platform metadata rows, tenant database schema,
     * and tenant storage folders on disk. Never deletes platform-level VERI*FACTU authorisations
     * or owner user accounts.
     */
    void deleteTenantCompletely(String tenantId);
}
