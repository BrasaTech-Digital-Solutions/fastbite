package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.domain.tenant.TenantLocation;

import java.util.List;
import java.util.Optional;

public interface TenantLocationPort {
    List<TenantLocation> findByOwner(String ownerUsername);

    /** Every location of the platform, for background work such as sending billing records. */
    List<TenantLocation> findAll();
    Optional<TenantLocation> findByTenantId(String tenantId);
    Optional<TenantLocation> findByCustomDomain(String customDomain);
    Optional<TenantLocation> findByStripeAccountId(String stripeAccountId);
    void updateStripeCapabilities(String stripeAccountId, boolean chargesEnabled, boolean detailsSubmitted);
    void save(TenantLocation location);
    void delete(String tenantId);
}
