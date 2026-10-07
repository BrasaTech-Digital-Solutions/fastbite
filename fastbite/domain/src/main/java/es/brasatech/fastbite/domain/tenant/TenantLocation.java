package es.brasatech.fastbite.domain.tenant;

public record TenantLocation(
    String id,
    String ownerUsername,
    String tenantId,
    String plan,
    String customDomain,
    String stripeAccountId,
    Boolean stripeChargesEnabled,
    Boolean stripeDetailsSubmitted
) {
    public TenantLocation(String id, String ownerUsername, String tenantId, String plan, String customDomain, String stripeAccountId) {
        this(id, ownerUsername, tenantId, plan, customDomain, stripeAccountId, false, false);
    }

    public TenantLocation(String id, String ownerUsername, String tenantId, String plan, String customDomain) {
        this(id, ownerUsername, tenantId, plan, customDomain, null, false, false);
    }

    public boolean isStripeVerified() {
        return Boolean.TRUE.equals(stripeChargesEnabled);
    }
}
