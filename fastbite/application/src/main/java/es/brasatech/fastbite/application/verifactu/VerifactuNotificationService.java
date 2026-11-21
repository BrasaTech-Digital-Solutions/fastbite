package es.brasatech.fastbite.application.verifactu;

import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.mail.AccountMailOutboxPort;
import es.brasatech.fastbite.application.mail.AccountMailOutboxService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.tenant.OwnerWorkspacePort;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.fiscal.SpanishTaxId;
import es.brasatech.fastbite.domain.tenant.SubscriptionPlan;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Evaluates VERI*FACTU hold/open state transitions for production locations and queues owner emails
 * through {@code public.account_email_outbox} once per transition.
 *
 * <p>Rules:
 * <ul>
 *   <li>New locations still in initial onboarding never receive {@code VERIFACTU_ON_HOLD}.</li>
 *   <li>{@code VERIFACTU_ON_HOLD} is sent only when an opened (or previously authorised) location
 *       loses the right to issue tickets.</li>
 *   <li>{@code VERIFACTU_OPENED_TRIAL} mentions the 30-day trial only when the trial actually
 *       starts on this opening for a non-Free location.</li>
 *   <li>{@code VERIFACTU_OPENED_FREE} omits trial wording for Free-plan locations (or when no
 *       trial started).</li>
 *   <li>{@code VERIFACTU_REOPENED} is used when a previously held location regains the right to
 *       issue tickets ("you can issue tickets again", no trial wording).</li>
 * </ul>
 */
@Service
public class VerifactuNotificationService {

    public static final String PURPOSE_ON_HOLD = "VERIFACTU_ON_HOLD";
    public static final String PURPOSE_OPENED_TRIAL = "VERIFACTU_OPENED_TRIAL";
    public static final String PURPOSE_OPENED_FREE = "VERIFACTU_OPENED_FREE";
    public static final String PURPOSE_REOPENED = "VERIFACTU_REOPENED";

    private final PlatformEnvironment environment;
    private final TenantLocationService locations;
    private final OwnerWorkspacePort onboarding;
    private final FiscalProfileService fiscalProfiles;
    private final VerifactuAuthorizationGate gate;
    private final VerifactuAuthorizationStore authStore;
    private final AccountMailOutboxPort outboxPort;
    private final AccountMailOutboxService outboxService;
    private final Clock clock;

    @Autowired
    public VerifactuNotificationService(
            PlatformEnvironment environment,
            TenantLocationService locations,
            OwnerWorkspacePort onboarding,
            FiscalProfileService fiscalProfiles,
            VerifactuAuthorizationGate gate,
            VerifactuAuthorizationStore authStore,
            AccountMailOutboxPort outboxPort,
            @Autowired(required = false) AccountMailOutboxService outboxService,
            Clock clock) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.locations = Objects.requireNonNull(locations, "locations");
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
        this.fiscalProfiles = Objects.requireNonNull(fiscalProfiles, "fiscalProfiles");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.authStore = Objects.requireNonNull(authStore, "authStore");
        this.outboxPort = Objects.requireNonNull(outboxPort, "outboxPort");
        this.outboxService = outboxService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Evaluates a single production location and queues/dispatches a notification if its state
     * transitioned.
     *
     * @param tenantId         location identifier
     * @param trialJustStarted true only when this opening actually started the 30-day trial
     * @return {@code true} when a new outbox notification was queued
     */
    public boolean evaluateLocation(String tenantId, boolean trialJustStarted) {
        return evaluateLocation(tenantId, trialJustStarted, true);
    }

    public boolean evaluateLocation(String tenantId, boolean trialJustStarted, boolean dispatchNow) {
        if (!environment.production() || tenantId == null || tenantId.isBlank()) {
            return false;
        }
        String normalizedTenant = tenantId.strip();
        Optional<TenantLocation> locOpt = locations.getLocation(normalizedTenant);
        if (locOpt.isEmpty() || locOpt.get().isClosed()) {
            return false;
        }
        TenantLocation location = locOpt.get();
        boolean opened = onboarding.read(normalizedTenant).opened();

        if (gate.holdReason(normalizedTenant).isPresent()) {
            if (!opened && !hasHistoricalValidAuthorization(normalizedTenant)) {
                return false;
            }
            return enqueue(normalizedTenant, PURPOSE_ON_HOLD, dispatchNow);
        }

        if (!opened) {
            return false;
        }
        boolean freePlan = location.plan() != null && SubscriptionPlan.FREE.name().equalsIgnoreCase(location.plan().strip());
        String purpose = (trialJustStarted && !freePlan) ? PURPOSE_OPENED_TRIAL : PURPOSE_OPENED_FREE;
        return enqueue(normalizedTenant, purpose, dispatchNow);
    }

    /** Evaluates all non-closed production locations currently using the given NIF, enqueueing and dispatching. */
    public int evaluateForNif(String nif) {
        return evaluateForNif(nif, true);
    }

    /**
     * Evaluates all non-closed production locations currently using the given NIF and enqueues outbox
     * rows without performing SMTP (used inside the 8-second webhook budget and refresh paths).
     */
    public int evaluateForNifEnqueueOnly(String nif) {
        return evaluateForNif(nif, false);
    }

    private int evaluateForNif(String nif, boolean dispatchNow) {
        if (!environment.production() || nif == null || nif.isBlank()) {
            return 0;
        }
        Optional<SpanishTaxId> parsed = SpanishTaxId.parse(nif);
        if (parsed.isEmpty()) {
            return 0;
        }
        String normalizedNif = parsed.get().value();
        int queued = 0;
        for (TenantLocation loc : locations.getAllLocations()) {
            if (loc.isClosed()) {
                continue;
            }
            FiscalProfile profile = inTenant(loc.tenantId(), fiscalProfiles::get);
            if (profile != null && profile.taxId() != null) {
                Optional<SpanishTaxId> locNif = SpanishTaxId.parse(profile.taxId());
                if (locNif.isPresent() && normalizedNif.equals(locNif.get().value())
                        && evaluateLocation(loc.tenantId(), false, dispatchNow)) {
                    queued++;
                }
            }
        }
        return queued;
    }

    /** Sweeps all non-closed production locations (called from {@code POST /internal/jobs/run}). */
    public int sweep() {
        if (!environment.production()) {
            return 0;
        }
        int queued = 0;
        for (TenantLocation loc : locations.getAllLocations()) {
            if (!loc.isClosed() && evaluateLocation(loc.tenantId(), false, false)) {
                queued++;
            }
        }
        return queued;
    }

    private boolean hasHistoricalValidAuthorization(String tenantId) {
        FiscalProfile profile = inTenant(tenantId, fiscalProfiles::get);
        if (profile == null || profile.taxId() == null || profile.taxId().isBlank()) {
            return false;
        }
        Optional<SpanishTaxId> parsed = SpanishTaxId.parse(profile.taxId());
        if (parsed.isEmpty()) {
            return false;
        }
        String nif = parsed.get().value();
        return authStore.findValidByNif(nif).isPresent()
                || authStore.findLatestByNif(nif).map(a -> a.validFrom() != null).orElse(false);
    }

    private boolean enqueue(String tenantId, String purpose, boolean dispatchNow) {
        boolean queued = outboxPort.enqueueVerifactuNotification(tenantId, purpose, clock.instant());
        if (queued && dispatchNow && outboxService != null) {
            outboxService.runForLocation(tenantId);
        }
        return queued;
    }

    private <T> T inTenant(String tenantId, Supplier<T> work) {
        String previous = TenantContext.getCurrentTenant();
        try {
            TenantContext.setCurrentTenant(tenantId);
            return work.get();
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.setCurrentTenant(previous);
            }
        }
    }
}
