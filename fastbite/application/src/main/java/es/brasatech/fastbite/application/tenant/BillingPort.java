package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.domain.tenant.BillingAccount;
import es.brasatech.fastbite.domain.tenant.PlatformBillingSettings;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

public interface BillingPort {
    Optional<BillingAccount> find(String tenant);
    List<BillingAccount> findAll();
    BillingAccount updateLocked(String tenant, UnaryOperator<BillingAccount> update);
    void requestGroupQuote(String owner, String email, int locations);
    PlatformBillingSettings getPlatformSettings();
    void updatePlatformSettings(PlatformBillingSettings settings);
    void updateTenantPlan(String tenantId, String plan);
    void extendTenantTrial(String tenantId, int extraDays);
    default void startTrialIfNeeded(String tenantId, Instant now) {
    }
    default Optional<String> resolveOwnerEmail(String tenantId, String ownerUsername) {
        return Optional.empty();
    }
    default void closeLocationData(String tenantId, Instant now) {
    }
}
