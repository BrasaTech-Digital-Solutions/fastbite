package es.brasatech.fastbite.service;

import es.brasatech.fastbite.application.fiscal.FiscalIssuerPort;
import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.fiscal.SpanishTaxId;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
public class FiscalIssuerBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(FiscalIssuerBackfillJob.class);

    private final FiscalIssuerPort fiscalIssuers;
    private final TenantLocationService locations;
    private final FiscalProfileService fiscalProfiles;
    private final PlatformEnvironment environment;
    private final Clock clock;

    public FiscalIssuerBackfillJob(FiscalIssuerPort fiscalIssuers,
                                   TenantLocationService locations,
                                   FiscalProfileService fiscalProfiles,
                                   PlatformEnvironment environment,
                                   Clock clock) {
        this.fiscalIssuers = fiscalIssuers;
        this.locations = locations;
        this.fiscalProfiles = fiscalProfiles;
        this.environment = environment;
        this.clock = clock;
    }

    public int run(Instant deadline) {
        if (!environment.production()) {
            return 0;
        }
        long startNanos = System.nanoTime();
        var all = locations.getAllLocations();
        int added = 0;
        for (var location : all) {
            if (!clock.instant().isBefore(deadline)) {
                break;
            }
            try {
                TenantContext.setCurrentTenant(location.tenantId());
                FiscalProfile profile = fiscalProfiles.get();
                if (profile != null && profile.taxId() != null && SpanishTaxId.isValid(profile.taxId())) {
                    var parsed = SpanishTaxId.parse(profile.taxId());
                    if (parsed.isPresent()) {
                        boolean inserted = fiscalIssuers.recordIfAbsent(parsed.get().value(), location.tenantId(), clock.instant());
                        if (inserted) {
                            added++;
                        }
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Could not backfill fiscal profile of {}: {}", location.tenantId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        log.info("Fiscal issuer backfill: checked {} locations, recorded {} new issuers in {} ms", all.size(), added, elapsedMs);
        return added;
    }
}
