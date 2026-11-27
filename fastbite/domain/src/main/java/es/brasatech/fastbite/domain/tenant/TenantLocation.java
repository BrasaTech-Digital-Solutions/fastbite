package es.brasatech.fastbite.domain.tenant;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public record TenantLocation(
    String id,
    String ownerUsername,
    String tenantId,
    String plan,
    String customDomain,
    String stripeAccountId,
    Boolean stripeChargesEnabled,
    Boolean stripeDetailsSubmitted,
    Instant closedAt,
    Instant pendingDeleteAt,
    Instant eraseAfter,
    Boolean stripeWasCanceling
) {
    public TenantLocation(String id, String ownerUsername, String tenantId, String plan, String customDomain,
            String stripeAccountId, Boolean stripeChargesEnabled, Boolean stripeDetailsSubmitted, Instant closedAt,
            Instant pendingDeleteAt, Instant eraseAfter) {
        this(id, ownerUsername, tenantId, plan, customDomain, stripeAccountId, stripeChargesEnabled, stripeDetailsSubmitted, closedAt, pendingDeleteAt, eraseAfter, null);
    }

    public TenantLocation(String id, String ownerUsername, String tenantId, String plan, String customDomain,
            String stripeAccountId, Boolean stripeChargesEnabled, Boolean stripeDetailsSubmitted, Instant closedAt) {
        this(id, ownerUsername, tenantId, plan, customDomain, stripeAccountId, stripeChargesEnabled, stripeDetailsSubmitted, closedAt, null, null, null);
    }

    public TenantLocation(String id, String ownerUsername, String tenantId, String plan, String customDomain,
            String stripeAccountId, Boolean stripeChargesEnabled, Boolean stripeDetailsSubmitted) {
        this(id, ownerUsername, tenantId, plan, customDomain, stripeAccountId, stripeChargesEnabled, stripeDetailsSubmitted, null, null, null, null);
    }

    public TenantLocation(String id, String ownerUsername, String tenantId, String plan, String customDomain, String stripeAccountId) {
        this(id, ownerUsername, tenantId, plan, customDomain, stripeAccountId, false, false, null, null, null, null);
    }

    public TenantLocation(String id, String ownerUsername, String tenantId, String plan, String customDomain) {
        this(id, ownerUsername, tenantId, plan, customDomain, null, false, false, null, null, null, null);
    }

    public boolean isStripeVerified() {
        return Boolean.TRUE.equals(stripeChargesEnabled);
    }

    public boolean isClosed() {
        return closedAt != null;
    }

    public boolean isPendingDelete() {
        return pendingDeleteAt != null;
    }

    public String closedLabel() {
        if (closedAt == null) return null;
        return "Closed on " + DateTimeFormatter.ofPattern("dd/MM/yyyy")
                .withZone(ZoneId.of("Europe/Madrid")).format(closedAt);
    }
}
