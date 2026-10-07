package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.TenantLocationPort;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class TenantLocationRepositoryAdapter implements TenantLocationPort {

    private final TenantLocationJpaRepository repository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Override
    public List<TenantLocation> findByOwner(String ownerUsername) {
        return repository.findByOwnerUsername(ownerUsername).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<TenantLocation> findByTenantId(String tenantId) {
        return repository.findByTenantId(tenantId)
                .map(this::toDomain);
    }

    @Override
    public Optional<TenantLocation> findByCustomDomain(String customDomain) {
        return repository.findByCustomDomain(customDomain)
                .map(this::toDomain);
    }

    @Override
    public Optional<TenantLocation> findByStripeAccountId(String stripeAccountId) {
        if (stripeAccountId == null || stripeAccountId.isBlank()) return Optional.empty();
        return repository.findByStripeAccountId(stripeAccountId.trim())
                .map(this::toDomain);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void updateStripeCapabilities(String stripeAccountId, boolean chargesEnabled, boolean detailsSubmitted) {
        if (stripeAccountId == null || stripeAccountId.isBlank()) return;
        repository.findByStripeAccountId(stripeAccountId.trim()).ifPresent(entity -> {
            entity.setStripeChargesEnabled(chargesEnabled);
            entity.setStripeDetailsSubmitted(detailsSubmitted);
            repository.saveAndFlush(entity);
        });
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void save(TenantLocation location) {
        TenantLocationEntity entity = repository.findByTenantId(location.tenantId())
                .orElse(new TenantLocationEntity());
        entity.setOwnerUsername(location.ownerUsername());
        entity.setTenantId(location.tenantId());
        String effectivePlan = (location.plan() != null && !location.plan().isBlank()) ? location.plan() : (entity.getPlan() != null ? entity.getPlan() : "RESTAURANT");
        entity.setPlan(effectivePlan);
        entity.setCustomDomain(location.customDomain());
        entity.setStripeAccountId(location.stripeAccountId());
        if (location.stripeChargesEnabled() != null) {
            entity.setStripeChargesEnabled(location.stripeChargesEnabled());
        }
        if (location.stripeDetailsSubmitted() != null) {
            entity.setStripeDetailsSubmitted(location.stripeDetailsSubmitted());
        }
        repository.saveAndFlush(entity);
        jdbc.update("INSERT INTO public.tenant_billing (tenant_id,billing_key) SELECT ?,? WHERE NOT EXISTS (SELECT 1 FROM public.tenant_billing WHERE tenant_id=?)",
                location.tenantId(), java.util.UUID.randomUUID().toString(), location.tenantId());
    }

    @Override
    public void delete(String tenantId) {
        repository.findByTenantId(tenantId)
                .ifPresent(entity -> repository.deleteById(entity.getId()));
    }

    private TenantLocation toDomain(TenantLocationEntity entity) {
        return new TenantLocation(entity.getId(), entity.getOwnerUsername(), entity.getTenantId(), entity.getPlan(),
                entity.getCustomDomain(), entity.getStripeAccountId(),
                Boolean.TRUE.equals(entity.getStripeChargesEnabled()),
                Boolean.TRUE.equals(entity.getStripeDetailsSubmitted()));
    }
}
