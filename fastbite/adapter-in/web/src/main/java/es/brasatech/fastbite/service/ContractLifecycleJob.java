package es.brasatech.fastbite.service;

import es.brasatech.fastbite.application.mail.ContractLifecycleMailPort;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.tenant.BillingPort;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.domain.tenant.BillingAccount;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Enforces the contract lifecycle for unpaid Restaurant-plan locations in production (terms §5 & §13, advisor B7):
 * <ul>
 *   <li>Records and emails the owner when orders pause (with the contract end date 30 days from the first notice);</li>
 *   <li>Emails a reminder 7 days before the contract ends;</li>
 *   <li>Ends the contract 30 days after the pause notice and emails the owner (with the data deletion date 30 days later);</li>
 *   <li>Regularising before the contract ends resets the pause clock; paying after the end but before deletion reinstates the contract;</li>
 *   <li>30 days after the contract ends (when {@code fastbite.contract.deletion-enabled=true}), deletes non-fiscal data
 *       outside the row lock, keeps all fiscal/VERI*FACTU records and privacy notices, and permanently closes the location
 *       while keeping its address reserved forever.</li>
 * </ul>
 */
@Component
public class ContractLifecycleJob {

    private static final Logger log = LoggerFactory.getLogger(ContractLifecycleJob.class);

    public static final int PAUSE_DAYS_BEFORE_CONTRACT_END = 30;
    public static final int WARNING_DAYS_BEFORE_CONTRACT_END = 7;
    public static final int EXPORT_DAYS_BEFORE_DELETION = 30;
    public static final Duration CLOSING_LEASE = Duration.ofMinutes(5);

    private final BillingPort billing;
    private final TenantLocationService locations;
    private final TenantBackupRestorePort backups;
    private final ContractLifecycleMailPort mail;
    private final TenantRoutingResolver routingResolver;
    private final PlatformEnvironment environment;
    private final boolean inProcess;
    private final boolean deletionEnabled;
    private final Clock clock;

    @Autowired
    public ContractLifecycleJob(BillingPort billing,
            TenantLocationService locations,
            TenantBackupRestorePort backups,
            ContractLifecycleMailPort mail,
            TenantRoutingResolver routingResolver,
            PlatformEnvironment environment,
            @Value("${fastbite.jobs.in-process:true}") boolean inProcess,
            @Value("${fastbite.contract.deletion-enabled:false}") boolean deletionEnabled,
            Clock clock) {
        this.billing = Objects.requireNonNull(billing, "billing");
        this.locations = Objects.requireNonNull(locations, "locations");
        this.backups = Objects.requireNonNull(backups, "backups");
        this.mail = Objects.requireNonNull(mail, "mail");
        this.routingResolver = Objects.requireNonNull(routingResolver, "routingResolver");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.inProcess = inProcess;
        this.deletionEnabled = deletionEnabled;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ContractLifecycleJob(BillingPort billing,
            TenantLocationService locations,
            TenantBackupRestorePort backups,
            ContractLifecycleMailPort mail,
            TenantRoutingResolver routingResolver,
            PlatformEnvironment environment,
            boolean inProcess,
            Clock clock) {
        this(billing, locations, backups, mail, routingResolver, environment, inProcess, false, clock);
    }

    public static Instant effectiveContractEndsAt(Instant pausedSince, Instant pauseNotifiedAt) {
        Instant start = pauseNotifiedAt != null && pauseNotifiedAt.isAfter(pausedSince)
                ? pauseNotifiedAt
                : pausedSince;
        return start.plus(PAUSE_DAYS_BEFORE_CONTRACT_END, ChronoUnit.DAYS);
    }

    @Scheduled(initialDelayString = "${fastbite.contract.lifecycle-delay:PT5M}",
            fixedDelayString = "${fastbite.contract.lifecycle-delay:PT1H}")
    public void checkLifecycle() {
        if (!inProcess) {
            return;
        }
        run(clock.instant().plus(VerifactuSubmissionJob.TIME_BUDGET));
    }

    public int run(Instant deadline) {
        if (!environment.production()) {
            return 0;
        }
        var candidates = billing.findAll();
        AtomicInteger actions = new AtomicInteger(0);
        for (BillingAccount candidate : candidates) {
            if (!clock.instant().isBefore(deadline)) {
                break;
            }
            if (candidate.isFreePlan() || candidate.isClosed()) {
                continue;
            }
            try {
                processLocation(candidate.tenantId(), actions);
            } catch (RuntimeException e) {
                log.warn("Contract lifecycle check failed for {}: {}", candidate.tenantId(), e.getMessage());
            }
        }
        return actions.get();
    }

    private enum DecisionKind {
        NONE,
        REINSTATED,
        REGULARISED,
        PAUSE_NOTICE,
        WARNING_NOTICE,
        CONTRACT_ENDED,
        WOULD_DELETE,
        START_CLOSING
    }

    private record Decision(
            DecisionKind kind,
            String owner,
            Instant pausedSince,
            Instant contractEndsAt,
            Instant endedAt,
            Instant deletionAt,
            Instant at) {
        static final Decision NONE = new Decision(DecisionKind.NONE, null, null, null, null, null, null);
    }

    private void processLocation(String tenantId, AtomicInteger actions) {
        Decision[] decisionHolder = new Decision[]{Decision.NONE};

        billing.updateLocked(tenantId, account -> {
            decisionHolder[0] = Decision.NONE;
            if (account.isFreePlan() || account.isClosed()) {
                return account;
            }
            Instant now = clock.instant();
            if (account.hasActiveBilling(now)) {
                if (account.contractEndedAt() != null) {
                    decisionHolder[0] = new Decision(DecisionKind.REINSTATED, account.owner(), null, null, null, null, now);
                    return account.withLifecycle(null, null, null, null, null, null);
                }
                if (account.pausedSince() != null || account.pauseNotifiedAt() != null) {
                    decisionHolder[0] = new Decision(DecisionKind.REGULARISED, account.owner(), null, null, null, null, now);
                    return account.withLifecycle(null, null, null, null, null, null);
                }
                return account;
            }

            if (account.contractEndedAt() != null) {
                Instant deletionAt = account.contractEndedAt().plus(EXPORT_DAYS_BEFORE_DELETION, ChronoUnit.DAYS);
                if (!now.isBefore(deletionAt)) {
                    if (!deletionEnabled) {
                        decisionHolder[0] = new Decision(DecisionKind.WOULD_DELETE, account.owner(), null, null,
                                account.contractEndedAt(), deletionAt, now);
                        return account;
                    }
                    if (account.closingStartedAt() != null
                            && now.isBefore(account.closingStartedAt().plus(CLOSING_LEASE))) {
                        return account;
                    }
                    decisionHolder[0] = new Decision(DecisionKind.START_CLOSING, account.owner(),
                            account.pausedSince(), null, account.contractEndedAt(), deletionAt, now);
                    return account.withLifecycle(account.pausedSince(), account.pauseNotifiedAt(),
                            account.warningNotifiedAt(), account.contractEndedAt(), now, null);
                }
                return account;
            }

            Instant pausedSince = account.computePausedSince(now);

            // The 30-day clock starts when the owner is first notified (or when the pause is first observed).
            if (account.pauseNotifiedAt() == null) {
                Instant contractEndsAt = effectiveContractEndsAt(pausedSince, now);
                decisionHolder[0] = new Decision(DecisionKind.PAUSE_NOTICE, account.owner(),
                        pausedSince, contractEndsAt, null, null, now);
                return account.withLifecycle(pausedSince, now, null, null, null, null);
            }

            Instant contractEndsAt = effectiveContractEndsAt(pausedSince, account.pauseNotifiedAt());

            if (!now.isBefore(contractEndsAt)) {
                Instant endedAt = now;
                Instant deletionAt = endedAt.plus(EXPORT_DAYS_BEFORE_DELETION, ChronoUnit.DAYS);
                decisionHolder[0] = new Decision(DecisionKind.CONTRACT_ENDED, account.owner(),
                        pausedSince, contractEndsAt, endedAt, deletionAt, now);
                return account.withLifecycle(
                        pausedSince,
                        account.pauseNotifiedAt(),
                        account.warningNotifiedAt(),
                        endedAt,
                        null,
                        null);
            }

            Instant warningStartsAt = contractEndsAt.minus(WARNING_DAYS_BEFORE_CONTRACT_END, ChronoUnit.DAYS);
            if (!now.isBefore(warningStartsAt) && account.warningNotifiedAt() == null) {
                decisionHolder[0] = new Decision(DecisionKind.WARNING_NOTICE, account.owner(),
                        pausedSince, contractEndsAt, null, null, now);
                return account.withLifecycle(
                        pausedSince,
                        account.pauseNotifiedAt(),
                        now,
                        null,
                        null,
                        null);
            }

            return account;
        });

        Decision d = decisionHolder[0];
        switch (d.kind()) {
            case NONE -> {
            }
            case REINSTATED -> {
                actions.incrementAndGet();
                log.info("ContractLifecycleJob: reinstated contract for {} after payment prior to data deletion", tenantId);
            }
            case REGULARISED -> {
                actions.incrementAndGet();
                log.info("ContractLifecycleJob: cleared pause clock for regularised location {}", tenantId);
            }
            case PAUSE_NOTICE -> {
                actions.incrementAndGet();
                sendNotice(tenantId, d.owner(), "pause notice",
                        addr -> mail.sendOrdersPausedNotice(addr, tenantId, d.pausedSince(), d.contractEndsAt()));
                log.info("ContractLifecycleJob: sent pause notice for {} (pausedSince={}, contractEndsAt={})",
                        tenantId, d.pausedSince(), d.contractEndsAt());
            }
            case WARNING_NOTICE -> {
                actions.incrementAndGet();
                sendNotice(tenantId, d.owner(), "7-day contract end warning",
                        addr -> mail.sendContractEndingWarning(addr, tenantId, d.contractEndsAt()));
                log.info("ContractLifecycleJob: sent 7-day contract end warning for {} (contractEndsAt={})",
                        tenantId, d.contractEndsAt());
            }
            case CONTRACT_ENDED -> {
                actions.incrementAndGet();
                sendNotice(tenantId, d.owner(), "contract ended notice",
                        addr -> mail.sendContractEndedNotice(addr, tenantId, d.endedAt(), d.deletionAt()));
                log.info("ContractLifecycleJob: ended contract for {} (contractEndedAt={}, deletionAt={})",
                        tenantId, d.endedAt(), d.deletionAt());
            }
            case WOULD_DELETE -> {
                log.info("ContractLifecycleJob: would delete non-fiscal data and close {}", tenantId);
            }
            case START_CLOSING -> {
                String customDomain = locations.getLocation(tenantId)
                        .map(TenantLocation::customDomain)
                        .orElse(null);
                backups.cleanTenantData(tenantId);
                billing.closeLocationData(tenantId, d.at());
                billing.updateLocked(tenantId, acc -> acc.withLifecycle(
                        acc.pausedSince(),
                        acc.pauseNotifiedAt(),
                        acc.warningNotifiedAt(),
                        acc.contractEndedAt(),
                        acc.closingStartedAt(),
                        d.at()));
                routingResolver.evictTenant(tenantId);
                if (customDomain != null) {
                    routingResolver.evictCache(customDomain);
                }
                actions.incrementAndGet();
                log.info("ContractLifecycleJob: deleted non-fiscal data and closed location {}", tenantId);
            }
        }
    }

    private void sendNotice(String tenantId, String owner, String noticeKind, Consumer<String> sender) {
        try {
            Optional<String> email = billing.resolveOwnerEmail(tenantId, owner);
            email.ifPresent(sender);
        } catch (RuntimeException e) {
            log.error("ContractLifecycleJob: failed to send {} for {}: {}", noticeKind, tenantId, e.getMessage());
        }
    }
}
