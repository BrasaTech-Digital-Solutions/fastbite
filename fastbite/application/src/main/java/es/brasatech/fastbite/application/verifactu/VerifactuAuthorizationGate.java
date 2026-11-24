package es.brasatech.fastbite.application.verifactu;

import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.fiscal.SpanishTaxId;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorization;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * Single server-side gate deciding whether a location may issue tickets/invoices now.
 *
 * <p>In sandbox deployments every location may issue training tickets. In production deployments,
 * there is no bypass of any kind: a location may issue tickets only when
 * {@code fastbite.verifactu.collaboration-active} is {@code true}, its fiscal profile has a valid
 * Spanish NIF, and {@code public.verifactu_authorizations} holds a currently {@code VALID}
 * authorisation for that NIF with both PDFs stored.
 */
@Service
public class VerifactuAuthorizationGate {

    public enum HoldReason {
        COLLABORATION_INACTIVE,
        FISCAL_INCOMPLETE,
        NOT_SIGNED,
        IN_PROGRESS,
        DOWNLOAD_FAILED,
        DECLINED,
        EXPIRED,
        CANCELLED,
        NIF_MISMATCH,
        NIF_UNVERIFIED,
        TEST_ENVIRONMENT,
        DOCUMENTS_GONE,
        REVOKED,
        SIGNING_UNAVAILABLE
    }

    private final PlatformEnvironment environment;
    private final FiscalProfileService fiscalProfiles;
    private final VerifactuAuthorizationService authorizations;

    public VerifactuAuthorizationGate(
            PlatformEnvironment environment,
            FiscalProfileService fiscalProfiles,
            VerifactuAuthorizationService authorizations) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.fiscalProfiles = Objects.requireNonNull(fiscalProfiles, "fiscalProfiles");
        this.authorizations = Objects.requireNonNull(authorizations, "authorizations");
    }

    /** True only when the given tenant may issue tickets/invoices right now. */
    public boolean mayIssueTickets(String tenantId) {
        return holdReason(tenantId).isEmpty();
    }

    /** True only when the current {@link TenantContext} tenant may issue tickets/invoices right now. */
    public boolean mayIssueTickets() {
        return mayIssueTickets(TenantContext.getCurrentTenant());
    }

    /** Throws {@link VerifactuAuthorizationRequiredException} when the given tenant is on hold. */
    public void requireMayIssueTickets(String tenantId) {
        Optional<HoldReason> reason = holdReason(tenantId);
        if (reason.isPresent()) {
            throw new VerifactuAuthorizationRequiredException(reason.get());
        }
    }

    /** Throws {@link VerifactuAuthorizationRequiredException} when the current tenant is on hold. */
    public void requireMayIssueTickets() {
        requireMayIssueTickets(TenantContext.getCurrentTenant());
    }

    /**
     * Returns why the given tenant is currently prevented from issuing tickets in production, or
     * {@link Optional#empty()} when ticket issuance is allowed (including always in sandbox).
     */
    public Optional<HoldReason> holdReason(String tenantId) {
        if (environment.sandbox()) {
            return Optional.empty();
        }
        String effectiveTenant = (tenantId == null || tenantId.isBlank())
                ? TenantContext.getCurrentTenant()
                : tenantId.strip();
        if (effectiveTenant == null || effectiveTenant.isBlank()) {
            return Optional.of(HoldReason.FISCAL_INCOMPLETE);
        }
        FiscalProfile profile = loadFiscalProfile(effectiveTenant);
        if (profile == null || profile.taxId() == null || profile.taxId().isBlank()) {
            return Optional.of(HoldReason.FISCAL_INCOMPLETE);
        }
        Optional<SpanishTaxId> parsedNif = SpanishTaxId.parse(profile.taxId());
        if (parsedNif.isEmpty()) {
            return Optional.of(HoldReason.FISCAL_INCOMPLETE);
        }
        String nif = parsedNif.get().value();
        if (authorizations.hasValidAuthorization(nif)) {
            if (!authorizations.isCollaborationActive()) {
                return Optional.of(HoldReason.COLLABORATION_INACTIVE);
            }
            return Optional.empty();
        }
        Optional<VerifactuAuthorization> currentOpt = authorizations.findCurrentForNif(nif);
        if (currentOpt.isEmpty()) {
            return Optional.of(authorizations.isSigningConfigured()
                    ? HoldReason.NOT_SIGNED
                    : HoldReason.SIGNING_UNAVAILABLE);
        }
        VerifactuAuthorization auth = currentOpt.get();
        return Optional.of(switch (auth.status()) {
            case IN_PROGRESS -> HoldReason.IN_PROGRESS;
            case DOWNLOAD_FAILED -> HoldReason.DOWNLOAD_FAILED;
            case DECLINED -> HoldReason.DECLINED;
            case EXPIRED -> HoldReason.EXPIRED;
            case CANCELLED -> HoldReason.CANCELLED;
            case NIF_MISMATCH -> HoldReason.NIF_MISMATCH;
            case NIF_UNVERIFIED -> HoldReason.NIF_UNVERIFIED;
            case TEST_ENVIRONMENT -> HoldReason.TEST_ENVIRONMENT;
            case LIVE_ENVIRONMENT -> HoldReason.NOT_SIGNED;
            case DOCUMENTS_GONE -> HoldReason.DOCUMENTS_GONE;
            case REVOKED -> HoldReason.REVOKED;
            case VALID -> {
                if (auth.revokedAt() != null) {
                    yield HoldReason.REVOKED;
                }
                if (!auth.hasStoredDocuments()) {
                    yield HoldReason.DOWNLOAD_FAILED;
                }
                yield HoldReason.NOT_SIGNED;
            }
        });
    }

    /** Returns why the current {@link TenantContext} tenant is on hold, or empty if allowed. */
    public Optional<HoldReason> holdReason() {
        return holdReason(TenantContext.getCurrentTenant());
    }

    private FiscalProfile loadFiscalProfile(String tenantId) {
        String previous = TenantContext.getCurrentTenant();
        boolean switched = !tenantId.equals(previous);
        if (switched) {
            TenantContext.setCurrentTenant(tenantId);
        }
        try {
            return fiscalProfiles.get();
        } finally {
            if (switched) {
                if (previous != null) {
                    TenantContext.setCurrentTenant(previous);
                } else {
                    TenantContext.clear();
                }
            }
        }
    }
}
