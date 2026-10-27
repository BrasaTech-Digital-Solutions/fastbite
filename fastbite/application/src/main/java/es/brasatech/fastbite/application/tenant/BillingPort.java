package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.domain.tenant.BillingAccount;
import java.util.Optional;
import java.util.function.UnaryOperator;

public interface BillingPort {
    Optional<BillingAccount> find(String tenant);
    java.util.List<BillingAccount> findAll();
    BillingAccount updateLocked(String tenant, UnaryOperator<BillingAccount> update);
    void requestGroupQuote(String owner, String email, int locations);
    es.brasatech.fastbite.domain.tenant.PlatformBillingSettings getPlatformSettings();
    void updatePlatformSettings(es.brasatech.fastbite.domain.tenant.PlatformBillingSettings settings);
    void updateTenantPlan(String tenantId, String plan);
    void extendTenantTrial(String tenantId, int extraDays);
    default java.util.Optional<String> resolveOwnerEmail(String tenantId, String ownerUsername) {
        return java.util.Optional.empty();
    }
    default void closeLocationData(String tenantId, java.time.Instant now) {
    }
}
