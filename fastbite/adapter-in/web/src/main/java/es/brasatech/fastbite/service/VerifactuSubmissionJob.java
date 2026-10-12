package es.brasatech.fastbite.service;

import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.VerifactuService;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Sends pending billing records of every location, oldest first. Until a submitter is configured
 * it does nothing, and the records wait safely. A failure in one location never stops the others.
 */
@Component
@Profile("jpa")
@Slf4j
public class VerifactuSubmissionJob {

    private final VerifactuService verifactu;
    private final TenantLocationService locations;
    private final PlatformEnvironment environment;

    public VerifactuSubmissionJob(VerifactuService verifactu, TenantLocationService locations,
            PlatformEnvironment environment) {
        this.verifactu = verifactu;
        this.locations = locations;
        this.environment = environment;
    }

    @Scheduled(initialDelayString = "${fastbite.verifactu.submit-delay:PT1M}",
            fixedDelayString = "${fastbite.verifactu.submit-delay:PT1M}")
    public void submitPending() {
        if (!verifactu.configured()) {
            return;
        }
        var all = locations.getAllLocations();
        // IndicadorMultiplesOT is worked out per owner: more than one location in FastBite
        Map<String, Long> perOwner = all.stream().filter(location -> location.ownerUsername() != null)
                .collect(Collectors.groupingBy(location -> location.ownerUsername(), Collectors.counting()));
        for (var location : all) {
            try {
                TenantContext.setCurrentTenant(location.tenantId());
                boolean multiple = perOwner.getOrDefault(Objects.toString(location.ownerUsername(), ""), 0L) > 1;
                int answered = verifactu.submitPending(environment.sandbox(), multiple);
                if (answered > 0) {
                    log.info("Sent {} billing records of {}", answered, location.tenantId());
                }
            } catch (RuntimeException e) {
                log.warn("Billing records of {} could not be sent: {}", location.tenantId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }
}
