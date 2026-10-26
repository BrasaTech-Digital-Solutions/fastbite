package es.brasatech.fastbite.service;

import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.VerifactuService;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Sends pending billing records of every location, oldest first. Until a submitter is configured
 * it does nothing, and the records wait safely. A failure in one location never stops the others.
 */
@Component
@Slf4j
public class VerifactuSubmissionJob {

    /**
     * Stops starting new locations after 4 minutes so an HTTP job run finishes well within Cloud
     * Run's 5-minute request timeout and Cloud Scheduler's 5-minute attempt deadline.
     */
    public static final Duration TIME_BUDGET = Duration.ofMinutes(4);

    public record Summary(int submitted, int locationsChecked, int locationsRemaining) {
    }

    private final VerifactuService verifactu;
    private final TenantLocationService locations;
    private final PlatformEnvironment environment;
    private final boolean inProcess;
    private final Clock clock;

    public VerifactuSubmissionJob(VerifactuService verifactu, TenantLocationService locations,
            PlatformEnvironment environment) {
        this(verifactu, locations, environment, true, Clock.systemUTC());
    }

    @Autowired
    public VerifactuSubmissionJob(VerifactuService verifactu, TenantLocationService locations,
            PlatformEnvironment environment,
            @Value("${fastbite.jobs.in-process:true}") boolean inProcess) {
        this(verifactu, locations, environment, inProcess, Clock.systemUTC());
    }

    public VerifactuSubmissionJob(VerifactuService verifactu, TenantLocationService locations,
            PlatformEnvironment environment, boolean inProcess, Clock clock) {
        this.verifactu = verifactu;
        this.locations = locations;
        this.environment = environment;
        this.inProcess = inProcess;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${fastbite.verifactu.submit-delay:PT1M}",
            fixedDelayString = "${fastbite.verifactu.submit-delay:PT1M}")
    public void submitPending() {
        if (!inProcess) {
            return;
        }
        run(clock.instant().plus(TIME_BUDGET));
    }

    public Summary run(Instant deadline) {
        if (!verifactu.configured()) {
            return new Summary(0, 0, 0);
        }
        var all = locations.getAllLocations();
        // IndicadorMultiplesOT is worked out per owner, counting every location ever created (see TenantLocationPort)
        Map<String, Long> perOwner = locations.countLocationsEverCreatedByOwner();
        int submitted = 0;
        int checked = 0;
        for (var location : all) {
            if (!clock.instant().isBefore(deadline)) {
                int remaining = all.size() - checked;
                log.info("Stopping billing record submission after {} locations ({} left for the next run)",
                        checked, remaining);
                return new Summary(submitted, checked, remaining);
            }
            checked++;
            try {
                TenantContext.setCurrentTenant(location.tenantId());
                boolean multiple = location.ownerUsername() != null
                        && perOwner != null
                        && perOwner.getOrDefault(location.ownerUsername(), 0L) > 1;
                int answered = verifactu.submitPending(environment.sandbox(), multiple);
                submitted += answered;
                if (answered > 0) {
                    log.info("Sent {} billing records of {}", answered, location.tenantId());
                }
            } catch (RuntimeException e) {
                log.warn("Billing records of {} could not be sent: {}", location.tenantId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
        return new Summary(submitted, checked, 0);
    }
}

