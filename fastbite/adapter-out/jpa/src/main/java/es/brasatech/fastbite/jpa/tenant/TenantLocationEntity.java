package es.brasatech.fastbite.jpa.tenant;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Platform-wide registry of locations. Pinned to the platform schema so lookups work
 * the same whichever tenant schema the current request is using.
 */
@Entity
@Table(name = "tenant_locations", schema = "public")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantLocationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "owner_username", nullable = false)
    private String ownerUsername;

    @Column(name = "tenant_id", unique = true, nullable = false)
    private String tenantId;

    @Column(name = "plan", nullable = false)
    private String plan;

    @Column(name = "custom_domain", unique = true)
    private String customDomain;

    @Column(name = "stripe_account_id")
    private String stripeAccountId;

    @Column(name = "stripe_charges_enabled")
    private Boolean stripeChargesEnabled;

    @Column(name = "stripe_details_submitted")
    private Boolean stripeDetailsSubmitted;
}
