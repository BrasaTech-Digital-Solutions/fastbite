package es.brasatech.fastbite.application.tenant;

import java.time.LocalDateTime;

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
     * Returns {@code true} if the tenant schema contains any order created in the given
     * day window that is still in progress (neither completed/cancelled nor delivered and paid,
     * excluding abandoned unpaid online takeaway drafts).
     */
    default boolean hasOrdersInProgressToday(String tenantId, LocalDateTime todayStart, LocalDateTime tomorrowStart) {
        return false;
    }

    /**
     * Returns {@code true} if the tenant is a protected built-in schema ({@code kebab}, {@code default}, {@code public}).
     */
    default boolean isProtectedTenant(String tenantId) {
        if (tenantId == null) {
            return true;
        }
        String clean = TenantRegistrationRules.tenantId(tenantId);
        return "kebab".equals(clean) || "default".equals(clean) || "public".equals(clean);
    }

    /**
     * Permanently deletes the tenant mapping, platform metadata rows, tenant database schema,
     * and tenant storage folders on disk. Never deletes platform-level VERI*FACTU authorisations,
     * NIF trial records, or owner user accounts.
     */
    void deleteTenantCompletely(String tenantId);
}
