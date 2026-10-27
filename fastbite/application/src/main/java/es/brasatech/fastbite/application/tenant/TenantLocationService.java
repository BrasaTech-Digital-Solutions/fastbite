package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.domain.tenant.TenantLocation;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class TenantLocationService {

    private final TenantLocationPort tenantLocationPort;

    public TenantLocationService(TenantLocationPort tenantLocationPort) {
        this.tenantLocationPort = tenantLocationPort;
    }

    public List<TenantLocation> getLocationsByOwner(String ownerUsername) {
        return tenantLocationPort.findByOwner(ownerUsername);
    }

    public List<TenantLocation> getAllLocations() {
        return tenantLocationPort.findAll();
    }

    public Optional<TenantLocation> getLocation(String tenantId) {
        return tenantLocationPort.findByTenantId(tenantId);
    }

    public Optional<TenantLocation> getLocationByCustomDomain(String customDomain) {
        if (customDomain == null || customDomain.trim().isEmpty()) {
            return Optional.empty();
        }
        return tenantLocationPort.findByCustomDomain(customDomain.trim().toLowerCase());
    }

    public void registerLocation(String ownerUsername, String tenantId, String plan) {
        if (tenantLocationPort.findByTenantId(tenantId).isPresent()) {
            throw new IllegalArgumentException("Tenant location prefix '" + tenantId + "' is already registered.");
        }
        TenantLocation location = new TenantLocation(null, ownerUsername, tenantId, plan, null);
        tenantLocationPort.save(location);
    }

    public void bindCustomDomain(String ownerUsername, String tenantId, String customDomain) {
        TenantLocation location = tenantLocationPort.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant location prefix '" + tenantId + "' not found."));
        if (!location.ownerUsername().equalsIgnoreCase(ownerUsername)) {
            throw new IllegalArgumentException("You do not own this location.");
        }
        
        if (customDomain != null && !customDomain.trim().isEmpty()) {
            String domainLower = customDomain.trim().toLowerCase();
            Optional<TenantLocation> existing = tenantLocationPort.findByCustomDomain(domainLower);
            if (existing.isPresent() && !existing.get().tenantId().equals(tenantId)) {
                throw new IllegalArgumentException("Custom domain '" + customDomain + "' is already mapped to another location.");
            }
        }
        
        TenantLocation updated = new TenantLocation(
                location.id(),
                location.ownerUsername(),
                location.tenantId(),
                location.plan(),
                (customDomain == null || customDomain.trim().isEmpty()) ? null : customDomain.trim().toLowerCase(),
                location.stripeAccountId()
        );
        tenantLocationPort.save(updated);
    }

    public void updateStripeAccountId(String ownerUsername, String tenantId, String stripeAccountId) {
        TenantLocation location = tenantLocationPort.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant location prefix '" + tenantId + "' not found."));
        if (!location.ownerUsername().equalsIgnoreCase(ownerUsername)) {
            throw new IllegalArgumentException("You do not own this location.");
        }
        TenantLocation updated = new TenantLocation(
                location.id(),
                location.ownerUsername(),
                location.tenantId(),
                location.plan(),
                location.customDomain(),
                (stripeAccountId == null || stripeAccountId.trim().isEmpty()) ? null : stripeAccountId.trim()
        );
        tenantLocationPort.save(updated);
    }

    public boolean isOwnerOf(String ownerUsername, String tenantId) {
        return tenantLocationPort.findByTenantId(tenantId)
                .map(loc -> loc.ownerUsername().equalsIgnoreCase(ownerUsername))
                .orElse(false);
    }

    public boolean isClosed(String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty()) return false;
        return tenantLocationPort.isClosed(tenantId.trim());
    }

    public Optional<TenantLocation> getLocationByStripeAccount(String stripeAccountId) {
        if (stripeAccountId == null || stripeAccountId.trim().isEmpty()) return Optional.empty();
        return tenantLocationPort.findByStripeAccountId(stripeAccountId.trim());
    }

    public void syncStripeCapabilities(String stripeAccountId, boolean chargesEnabled, boolean detailsSubmitted) {
        if (stripeAccountId == null || stripeAccountId.trim().isEmpty()) return;
        tenantLocationPort.updateStripeCapabilities(stripeAccountId.trim(), chargesEnabled, detailsSubmitted);
    }

    public void removeLocation(String tenantId) {
        tenantLocationPort.delete(tenantId);
    }

    /**
     * Map from owner username to the count of every location that owner has ever created
     * (including closed or deleted locations), for AEAT VERI*FACTU IndicadorMultiplesOT.
     */
    public java.util.Map<String, Long> countLocationsEverCreatedByOwner() {
        return tenantLocationPort.countLocationsEverCreatedByOwner();
    }
}
