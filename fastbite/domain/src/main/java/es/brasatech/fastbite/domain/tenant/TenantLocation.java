package es.brasatech.fastbite.domain.tenant;

public record TenantLocation(
    String id,
    String ownerUsername,
    String tenantId,
    String plan,
    String customDomain,
    String stripeAccountId,
    Boolean stripeChargesEnabled,
    Boolean stripeDetailsSubmitted,
    java.time.Instant closedAt
) {
    public TenantLocation(String id, String ownerUsername, String tenantId, String plan, String customDomain,
            String stripeAccountId, Boolean stripeChargesEnabled, Boolean stripeDetailsSubmitted) {
        this(id, ownerUsername, tenantId, plan, customDomain, stripeAccountId, stripeChargesEnabled, stripeDetailsSubmitted, null);
    }

    public TenantLocation(String id, String ownerUsername, String tenantId, String plan, String customDomain, String stripeAccountId) {
        this(id, ownerUsername, tenantId, plan, customDomain, stripeAccountId, false, false, null);
    }

    public TenantLocation(String id, String ownerUsername, String tenantId, String plan, String customDomain) {
        this(id, ownerUsername, tenantId, plan, customDomain, null, false, false, null);
    }

    public boolean isStripeVerified() {
        return Boolean.TRUE.equals(stripeChargesEnabled);
    }

    public boolean isClosed() {
        return closedAt != null;
    }

    public String closedLabel() {
        if (closedAt == null) return null;
        return "Closed on " + java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")
                .withZone(java.time.ZoneId.of("Europe/Madrid")).format(closedAt);
    }
}
