package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.TenantLocationPort;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = es.brasatech.fastbite.jpa.TestConfig.class)
class TenantLocationRepositoryAdapterTest {

    @Autowired
    private TenantLocationPort port;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void countsLocationsEverCreatedByOwnerIncludingDeletedAndLifecycle() {
        String owner = "historyowner" + System.nanoTime();
        String loc1 = "hloc1" + (System.nanoTime() % 100000);
        String loc2 = "hloc2" + (System.nanoTime() % 100000);

        // Register two locations in tenant_locations
        port.save(new TenantLocation(null, owner, loc1, "RESTAURANT", null));
        port.save(new TenantLocation(null, owner, loc2, "RESTAURANT", null));

        // Also simulate a lifecycle record for loc2 (as registration does)
        jdbc.update("INSERT INTO public.tenant_lifecycle (tenant_id, operation_id, owner_username, state, updated_at) " +
                "VALUES (?, ?, ?, 'ACTIVE', 0)", loc2, "setup:" + loc2, owner);

        Map<String, Long> initialCounts = port.countLocationsEverCreatedByOwner();
        assertThat(initialCounts.get(owner)).isEqualTo(2L);

        // Now remove loc2 from tenant_locations (simulating closure/deletion)
        port.delete(loc2);

        // The count must still remain 2 because tenant_lifecycle retains loc2!
        Map<String, Long> countsAfterDeletion = port.countLocationsEverCreatedByOwner();
        assertThat(countsAfterDeletion.get(owner)).as("Historical count cannot shrink when location is deleted").isEqualTo(2L);
    }

    @Test
    void singleLocationOwnerGetsCountOne() {
        String owner = "singleowner" + System.nanoTime();
        String loc = "sloc" + (System.nanoTime() % 100000);

        port.save(new TenantLocation(null, owner, loc, "RESTAURANT", null));

        Map<String, Long> counts = port.countLocationsEverCreatedByOwner();
        assertThat(counts.get(owner)).isEqualTo(1L);
    }
}
