package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.application.platform.PlatformAdmins;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.domain.tenant.BillingAccount;
import es.brasatech.fastbite.domain.tenant.SubscriptionPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class SubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    private final BillingPort billing;
    private final SubscriptionGateway gateway;
    private final PlatformEnvironment environment;
    private final PlatformAdmins admins;
    private final NifTrialPort nifTrials;
    private final Clock clock;
    private static final Set<String> STATES = Set.of("active", "trialing", "past_due", "unpaid", "canceled", "incomplete", "incomplete_expired", "paused");

    @Autowired
    public SubscriptionService(BillingPort billing, SubscriptionGateway gateway,
            PlatformEnvironment environment,
            PlatformAdmins admins,
            @Autowired(required = false) NifTrialPort nifTrials,
            @Autowired(required = false) Clock clock) {
        this.billing = Objects.requireNonNull(billing, "billing");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.admins = Objects.requireNonNull(admins, "admins");
        this.nifTrials = nifTrials;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public SubscriptionService(BillingPort billing, SubscriptionGateway gateway,
            PlatformEnvironment environment,
            PlatformAdmins admins) {
        this(billing, gateway, environment, admins, null, Clock.systemUTC());
    }

    public BillingAccount account(String tenant, String owner) {
        BillingAccount account = billing.find(tenant).orElseThrow(() -> new IllegalArgumentException("Billing is not available for this location."));
        requireOwner(account, owner);
        return overlayNifTrial(account);
    }
    public boolean configured() { return gateway.configured(); }
    public boolean canTakeOrders(String tenant) {
        return tenant != null && billing.find(tenant)
                .map(this::overlayNifTrial)
                .map(a -> a.canTakeOrders(clock.instant(), environment.production()))
                .orElse(false);
    }
    public boolean isFreePlan(String tenant) {
        return tenant != null && billing.find(tenant).map(BillingAccount::isFreePlan).orElse(false);
    }
    public boolean isTrialNotStarted(String tenant) {
        return tenant != null && billing.find(tenant).map(BillingAccount::trialNotStarted).orElse(false);
    }
    public boolean isClosed(String tenant) {
        return tenant != null && billing.find(tenant).map(BillingAccount::isClosed).orElse(false);
    }
    public void startTrialIfNeeded(String tenant, Instant now) {
        startTrialIfNeeded(tenant, null, now);
    }
    public void startTrialIfNeeded(String tenant, String taxId, Instant now) {
        if (tenant == null || now == null) {
            return;
        }
        Instant effectiveStart = now;
        if (environment.production() && nifTrials != null) {
            String resolvedNif = (taxId != null && !taxId.isBlank())
                    ? taxId
                    : nifTrials.findNifForTenant(tenant).orElse(null);
            if (resolvedNif != null && !resolvedNif.isBlank()) {
                NifTrialPort.NifTrial nifTrial = nifTrials.startOrGet(resolvedNif, now, SubscriptionPlan.TRIAL_DAYS);
                effectiveStart = nifTrial.trialEndsAt().minus(SubscriptionPlan.TRIAL_DAYS, ChronoUnit.DAYS);
            }
        }
        billing.startTrialIfNeeded(tenant, effectiveStart);
    }
    public Optional<NifTrialPort.NifTrial> findNifTrial(String taxId) {
        if (!environment.production() || nifTrials == null || taxId == null || taxId.isBlank()) {
            return Optional.empty();
        }
        return nifTrials.findByNif(taxId);
    }
    public Optional<NifTrialPort.NifTrial> findNifTrialForTenant(String tenantId) {
        if (!environment.production() || nifTrials == null || tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        return nifTrials.findNifForTenant(tenantId).flatMap(nifTrials::findByNif);
    }
    public static long remainingTrialDays(Instant trialEndsAt, Instant now) {
        if (trialEndsAt == null || now == null || !trialEndsAt.isAfter(now)) {
            return 0L;
        }
        long seconds = Duration.between(now, trialEndsAt).toSeconds();
        return Math.max(1L, (seconds + 86_399L) / 86_400L);
    }
    private BillingAccount overlayNifTrial(BillingAccount account) {
        if (!environment.production() || nifTrials == null || account == null
                || !"LOCAL_TRIAL".equals(account.status()) || account.trialStartedAt() == null) {
            return account;
        }
        Optional<NifTrialPort.NifTrial> nifTrial = nifTrials.findNifForTenant(account.tenantId())
                .flatMap(nifTrials::findByNif);
        if (nifTrial.isEmpty()) {
            return account;
        }
        Instant effectiveStart = nifTrial.get().trialEndsAt().minus(SubscriptionPlan.TRIAL_DAYS, ChronoUnit.DAYS);
        if (effectiveStart.equals(account.trialStartedAt())) {
            return account;
        }
        return account.withTrialStartedAt(effectiveStart);
    }
    public Optional<String> resolveOwnerEmail(String tenant, String ownerUsername) {
        return tenant == null ? Optional.empty() : billing.resolveOwnerEmail(tenant, ownerUsername);
    }
    public String checkout(String tenant, String owner) {
        var existing = account(tenant, owner);
        if (existing.isClosed()) throw new IllegalArgumentException("This location is closed and its contract has ended.");
        if (!gateway.configured()) throw new IllegalArgumentException("Subscription checkout is not available yet. Contact the platform operator.");
        // Commit the attempt BEFORE calling Stripe. A timeout or process crash must not lose its identity.
        billing.updateLocked(tenant, account -> {
            requireOwner(account, owner);
            if (account.checkoutId() != null && account.checkoutId().startsWith("pending:")) return account;
            if (account.subscriptionId() != null) {
                var current = gateway.subscription(account.subscriptionId());
                if (!Set.of("canceled", "incomplete_expired").contains(current.status()))
                    throw new IllegalArgumentException("A subscription already exists. Use Manage billing to update it.");
            }
            if (account.checkoutId() != null) {
                var pending = gateway.checkout(account.checkoutId());
                if (pending.complete() && !pending.subscriptionId().equals(account.subscriptionId()))
                    throw new IllegalArgumentException("Your subscription is being confirmed. Refresh billing shortly.");
                if (!pending.complete() && pending.expires() > Instant.now().getEpochSecond() && pending.url() != null) return account;
            }
            return account.reserveCheckout(Instant.now().getEpochSecond() + 23 * 3600);
        });
        var ready = billing.updateLocked(tenant, account -> {
            requireOwner(account, owner);
            if (!account.checkoutId().startsWith("pending:")) return account;
            if (account.checkoutExpires() < Instant.now().getEpochSecond() + 31 * 60)
                throw new IllegalArgumentException("This checkout needs a billing review. Contact support before trying again.");
            var session = gateway.createCheckout(overlayNifTrial(account), "fastbite-subscription-" + account.billingKey() + "-" + account.attempt());
            return account.checkout(session.id(), session.url(), session.expires());
        });
        return ready.checkoutUrl() != null ? ready.checkoutUrl() : "/owner/billing/" + tenant;
    }
    public String portal(String tenant, String owner) {
        var account = account(tenant, owner);
        if (account.customerId() == null) throw new IllegalArgumentException("Activate a subscription before managing billing.");
        return gateway.portal(account.customerId());
    }
    public void refreshCheckout(String checkoutId) {
        var session = gateway.checkout(checkoutId);
        if (!session.complete() || session.subscriptionId() == null || session.tenant() == null) return;
        if (billing.find(session.tenant()).isEmpty()) return;
        billing.updateLocked(session.tenant(), account -> {
            if (!checkoutId.equals(account.checkoutId())) {
                if (account.checkoutId() == null || !account.checkoutId().startsWith("pending:")
                        || !account.billingKey().equals(session.billingKey()) || !String.valueOf(account.attempt()).equals(session.attempt())) return account;
            }
            var linked = account.checkout(session.id(), session.url(), session.expires());
            return apply(linked, gateway.subscription(session.subscriptionId()));
        });
    }
    public void refreshSubscription(String subscriptionId) {
        var first = gateway.subscription(subscriptionId);
        if (first.tenant() == null || billing.find(first.tenant()).isEmpty()) return;
        billing.updateLocked(first.tenant(), account -> {
            if (!subscriptionId.equals(account.subscriptionId())) {
                if (account.checkoutId() == null || account.checkoutId().startsWith("pending:")) return account;
                var checkout = gateway.checkout(account.checkoutId());
                if (!checkout.complete() || !subscriptionId.equals(checkout.subscriptionId())) return account;
            }
            // Fetch inside the row lock: delayed events cannot overwrite a newer Stripe state.
            return apply(account, gateway.subscription(subscriptionId));
        });
    }
    public void refresh(String tenant, String owner) {
        var account = account(tenant, owner);
        if (account.checkoutId() != null && !account.checkoutId().startsWith("pending:")) refreshCheckout(account.checkoutId());
        account = account(tenant, owner);
        if (account.subscriptionId() != null) refreshSubscription(account.subscriptionId());
    }
    public void cancelSubscriptionImmediately(String tenant, String owner) {
        var existingOpt = billing.find(tenant);
        if (existingOpt.isEmpty()) return;
        var existing = existingOpt.get();
        requireOwner(existing, owner);
        if (existing.subscriptionId() == null || Set.of("canceled", "incomplete_expired").contains(existing.status())) {
            return;
        }
        if (!gateway.configured()) {
            throw new IllegalStateException("Billing is temporarily unavailable. Please try again shortly.");
        }
        gateway.cancelImmediately(existing.subscriptionId());
    }
    public void cancelSubscriptionAtPeriodEnd(String tenant, String owner) {
        var existingOpt = billing.find(tenant);
        if (existingOpt.isEmpty()) return;
        var existing = existingOpt.get();
        requireOwner(existing, owner);
        if (existing.subscriptionId() == null
                || Set.of("canceled", "incomplete_expired").contains(existing.status())
                || existing.cancelAtPeriodEnd()) {
            return;
        }
        if (!gateway.configured()) {
            throw new IllegalStateException("Billing is temporarily unavailable. Please try again shortly.");
        }
        var updated = gateway.cancelAtPeriodEnd(existing.subscriptionId());
        if (updated != null) {
            billing.updateLocked(tenant, account -> account.subscribed(
                    updated.customerId() != null ? updated.customerId() : account.customerId(),
                    updated.id() != null ? updated.id() : account.subscriptionId(),
                    updated.status() != null ? updated.status() : account.status(),
                    updated.periodEnd() > 0 ? updated.periodEnd() : account.periodEnd(),
                    updated.trialEnd() > 0 ? updated.trialEnd() : account.stripeTrialEnd(),
                    updated.cancelAtPeriodEnd()));
        }
    }
    /**
     * Prevents renewals or trial-end charges while a location is hidden during its undo window
     * by setting Stripe {@code cancel_at_period_end = true}.
     *
     * @return the previous {@code cancelAtPeriodEnd} state ({@code false} if flipped by us,
     *         {@code true} if already canceling), or {@code null} when no active Stripe subscription exists
     */
    public Boolean pauseBillingForPendingDelete(String tenant, String owner) {
        var existingOpt = billing.find(tenant);
        if (existingOpt.isEmpty()) return null;
        var existing = existingOpt.get();
        requireOwner(existing, owner);
        if (existing.subscriptionId() == null
                || Set.of("canceled", "incomplete_expired").contains(existing.status())) {
            return null;
        }
        boolean wasCanceling = existing.cancelAtPeriodEnd();
        if (!wasCanceling) {
            cancelSubscriptionAtPeriodEnd(tenant, owner);
        }
        return wasCanceling;
    }
    /**
     * Restores the Stripe subscription to its exact state before the location was hidden.
     * Only clears {@code cancel_at_period_end} when {@code previousCancelAtPeriodEnd} was {@code false}.
     */
    public void restoreBillingAfterUndo(String tenant, String owner, Boolean previousCancelAtPeriodEnd) {
        if (!Boolean.FALSE.equals(previousCancelAtPeriodEnd)) {
            return;
        }
        var existingOpt = billing.find(tenant);
        if (existingOpt.isEmpty()) return;
        var existing = existingOpt.get();
        requireOwner(existing, owner);
        if (existing.subscriptionId() == null
                || Set.of("canceled", "incomplete_expired").contains(existing.status())
                || !existing.cancelAtPeriodEnd()) {
            return;
        }
        if (!gateway.configured()) {
            throw new IllegalStateException("Billing is temporarily unavailable. Please try again shortly.");
        }
        var updated = gateway.resumeAtPeriodEnd(existing.subscriptionId());
        if (updated != null) {
            billing.updateLocked(tenant, account -> account.subscribed(
                    updated.customerId() != null ? updated.customerId() : account.customerId(),
                    updated.id() != null ? updated.id() : account.subscriptionId(),
                    updated.status() != null ? updated.status() : account.status(),
                    updated.periodEnd() > 0 ? updated.periodEnd() : account.periodEnd(),
                    updated.trialEnd() > 0 ? updated.trialEnd() : account.stripeTrialEnd(),
                    updated.cancelAtPeriodEnd()));
        }
    }
    private BillingAccount apply(BillingAccount account, SubscriptionGateway.Subscription sub) {
        if (!account.tenantId().equals(sub.tenant()) || !account.owner().equals(sub.owner())
                || !account.billingKey().equals(sub.billingKey()) || !sub.supportedPrice()
                || !STATES.contains(sub.status()) || sub.customerId() == null
                || (account.customerId() != null && !account.customerId().equals(sub.customerId())))
            throw new IllegalArgumentException("Subscription does not match this restaurant and plan.");
        return account.subscribed(sub.customerId(), sub.id(), sub.status(), sub.periodEnd(), sub.trialEnd(), sub.cancelAtPeriodEnd());
    }
    private static void requireOwner(BillingAccount account, String owner) {
        if (owner == null || !owner.equals(account.owner())) throw new IllegalArgumentException("You do not own this location.");
    }
    public void requestGroupQuote(String owner, String email, int locations) {
        if (email == null || email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || locations < 3 || locations > 1000)
            throw new IllegalArgumentException("Enter a valid email and between 3 and 1000 locations.");
        billing.requestGroupQuote(owner, email, locations);
    }
    public es.brasatech.fastbite.domain.tenant.PlatformBillingSettings platformSettings() {
        return billing.getPlatformSettings();
    }
    public void updatePlatformSettings(es.brasatech.fastbite.domain.tenant.PlatformBillingSettings settings) {
        if (settings.monthlyPriceCents() < 0 || settings.trialDays() < 0 || settings.freePlanCutBps() < 0) {
            throw new IllegalArgumentException("Billing parameters cannot be negative.");
        }
        billing.updatePlatformSettings(settings);
    }
    public void updateTenantPlan(String tenantId, String owner, String plan) {
        var existing = account(tenantId, owner);
        if (existing.isClosed()) throw new IllegalArgumentException("This location is closed and its contract has ended.");
        billing.updateTenantPlan(tenantId, plan);
    }
    public java.util.List<BillingAccount> listAllAccounts(String admin) {
        if (!admins.isAdmin(admin)) throw new IllegalArgumentException("Only a platform administrator may view all trials.");
        return billing.findAll();
    }
    public BillingAccount extendTenantTrial(String tenantId, String admin, int extraDays) {
        if (!admins.isAdmin(admin)) throw new IllegalArgumentException("Only a platform administrator may extend a trial.");
        if (extraDays < 1 || extraDays > 180) throw new IllegalArgumentException("Trial extension must be between 1 and 180 days.");
        var existing = billing.find(tenantId).orElseThrow(() -> new IllegalArgumentException("Billing is not available for this location."));
        if (existing.isClosed()) throw new IllegalArgumentException("This location is closed and its contract has ended.");
        if (existing.trialNotStarted()) throw new IllegalArgumentException("Trial has not started yet.");
        billing.extendTenantTrial(tenantId, extraDays);
        if (environment.production() && nifTrials != null) {
            nifTrials.findNifForTenant(tenantId).ifPresent(nif ->
                    nifTrials.extendByDays(nif, extraDays, clock.instant()).ifPresent(extended -> {
                        Instant effectiveStart = extended.trialEndsAt().minus(SubscriptionPlan.TRIAL_DAYS, ChronoUnit.DAYS);
                        List<String> siblings = nifTrials.findTenantsForNif(nif);
                        for (String sibling : siblings) {
                            billing.setTrialStartedAt(sibling, effectiveStart);
                        }
                    }));
        }
        BillingAccount updated = billing.find(tenantId)
                .map(this::overlayNifTrial)
                .orElseThrow(() -> new IllegalArgumentException("Billing is not available for this location."));
        log.info("Platform admin '{}' extended trial for location '{}' by {} days (new trial end: {})",
                admin.trim(), tenantId, extraDays, updated.trialEndsAt());
        return updated;
    }
}
