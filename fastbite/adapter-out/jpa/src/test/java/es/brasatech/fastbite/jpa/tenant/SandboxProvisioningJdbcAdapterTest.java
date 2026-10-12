package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.platform.SandboxProvisioningPort;
import es.brasatech.fastbite.jpa.TestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestConfig.class)
@ActiveProfiles("jpa")
class SandboxProvisioningJdbcAdapterTest {

    @Autowired SandboxProvisioningJdbcAdapter queue;

    static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private static String tenant() {
        return "queue" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void aLocationIsListedOnceAndDueUntilTheSandboxHasIt() {
        String tenant = tenant();
        queue.enqueue(tenant, "ana", "RESTAURANT", NOW);
        queue.enqueue(tenant, "ana", "RESTAURANT", NOW.plusSeconds(5));

        assertThat(queue.due(NOW, 100)).filteredOn(e -> e.tenantId().equals(tenant)).singleElement().satisfies(entry -> {
            assertThat(entry.ownerUsername()).isEqualTo("ana");
            assertThat(entry.plan()).isEqualTo("RESTAURANT");
            assertThat(entry.state()).isEqualTo(SandboxProvisioningPort.State.PENDING);
            assertThat(entry.attempts()).isZero();
        });

        queue.failed(tenant, "The sandbox could not be reached", NOW.plusSeconds(30));
        assertThat(queue.due(NOW.plusSeconds(10), 100)).noneMatch(e -> e.tenantId().equals(tenant));
        assertThat(queue.find(tenant)).get().satisfies(entry -> {
            assertThat(entry.attempts()).isEqualTo(1);
            assertThat(entry.lastError()).contains("could not be reached");
        });
        assertThat(queue.due(NOW.plusSeconds(30), 100)).anyMatch(e -> e.tenantId().equals(tenant));

        queue.done(tenant, NOW.plusSeconds(31));
        assertThat(queue.find(tenant)).get().extracting(SandboxProvisioningPort.Entry::state)
                .isEqualTo(SandboxProvisioningPort.State.DONE);
        assertThat(queue.due(NOW.plusSeconds(3600), 100)).noneMatch(e -> e.tenantId().equals(tenant));
    }
}
