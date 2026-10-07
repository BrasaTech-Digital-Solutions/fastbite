package es.brasatech.fastbite.jpa.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantLocationJpaRepository extends JpaRepository<TenantLocationEntity, String> {
    List<TenantLocationEntity> findByOwnerUsername(String ownerUsername);
    Optional<TenantLocationEntity> findByTenantId(String tenantId);
    Optional<TenantLocationEntity> findByCustomDomain(String customDomain);
    Optional<TenantLocationEntity> findByStripeAccountId(String stripeAccountId);
}
