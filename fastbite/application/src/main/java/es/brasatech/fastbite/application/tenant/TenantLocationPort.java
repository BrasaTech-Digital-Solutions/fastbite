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

    /**
     * Map from owner username to the count of every location that owner has ever created
     * (including closed or deleted locations), for AEAT VERI*FACTU IndicadorMultiplesOT.
     * AEAT developer FAQ 4-12-2025 section 4: "este valor deberá calcularse de forma
     * independiente por cada usuario del SIF SaaS (no a nivel global del SIF SaaS) y se
     * informará con \"S\" en todos los registros de facturación (y, en su caso, de evento)
     * de aquellos usuarios que tengan creadas más de una facturación en el SIF SaaS,
     * independientemente del estado de dichas facturaciones (alta, baja…) y de si son de
     * igual o de distinto OEF".
     */
    java.util.Map<String, Long> countLocationsEverCreatedByOwner();
}
