package es.brasatech.fastbite.jpa.fiscal;

import es.brasatech.fastbite.application.fiscal.FiscalIssuerPort;
import es.brasatech.fastbite.application.tenant.TenantLocationPort;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = es.brasatech.fastbite.jpa.TestConfig.class)
class FiscalIssuerJdbcIntegrationTest {

    @Autowired
    private FiscalIssuerPort fiscalIssuers;

    @Autowired
    private TenantLocationPort locations;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void recordIfAbsentIsIdempotentAndHandlesNulls() {
        String taxId = "B" + (10000000 + (int)(Math.random() * 80000000)) + "4";
        String tenant = "t" + System.nanoTime();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        assertThat(fiscalIssuers.recordIfAbsent(null, tenant, now)).isFalse();
        assertThat(fiscalIssuers.recordIfAbsent(taxId, null, now)).isFalse();
        assertThat(fiscalIssuers.recordIfAbsent(taxId, tenant, null)).isFalse();

        boolean first = fiscalIssuers.recordIfAbsent(taxId, tenant, now);
        assertThat(first).isTrue();

        boolean second = fiscalIssuers.recordIfAbsent(taxId, tenant, now.plusSeconds(60));
        assertThat(second).isFalse();
    }

    @Test
    void findPriorUsageByOwnerReturnsDateForSameOwnerAndEmptyForOtherOwner() {
        String ownerA = "ownerA" + System.nanoTime();
        String ownerB = "ownerB" + System.nanoTime();
        String loc1 = "loc1" + (System.nanoTime() % 100000);
        String loc2 = "loc2" + (System.nanoTime() % 100000);
        String loc3 = "loc3" + (System.nanoTime() % 100000);

        locations.save(new TenantLocation(null, ownerA, loc1, "RESTAURANT", null));
        locations.save(new TenantLocation(null, ownerA, loc2, "RESTAURANT", null));
        locations.save(new TenantLocation(null, ownerB, loc3, "RESTAURANT", null));

        String taxId = "A" + (10000000 + (int)(Math.random() * 80000000)) + "8";
        Instant t1 = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MILLIS);

        fiscalIssuers.recordIfAbsent(taxId, loc1, t1);

        // Same owner on another location: finds prior usage date
        Optional<Instant> priorForOwnerA = fiscalIssuers.findPriorUsageByOwner(ownerA, taxId, loc2);
        assertThat(priorForOwnerA).isPresent();
        assertThat(priorForOwnerA.get()).isEqualTo(t1);

        // Same owner on same location: empty (not a prior location)
        assertThat(fiscalIssuers.findPriorUsageByOwner(ownerA, taxId, loc1)).isEmpty();

        // Other owner on their location: empty (privacy: never reveals another owner's usage)
        assertThat(fiscalIssuers.findPriorUsageByOwner(ownerB, taxId, loc3)).isEmpty();

        // If loc1 is removed from tenant_locations but exists in tenant_lifecycle:
        locations.delete(loc1);
        jdbc.update("INSERT INTO public.tenant_lifecycle (tenant_id, operation_id, owner_username, state, updated_at) " +
                "VALUES (?, ?, ?, 'ACTIVE', 0)", loc1, "setup:" + loc1, ownerA);

        Optional<Instant> priorAfterDeletion = fiscalIssuers.findPriorUsageByOwner(ownerA, taxId, loc2);
        assertThat(priorAfterDeletion).isPresent();
        assertThat(priorAfterDeletion.get()).isEqualTo(t1);
    }
}
