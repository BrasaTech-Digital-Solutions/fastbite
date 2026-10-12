package es.brasatech.fastbite.application.platform;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxProvisioningServiceTest {

    static final String SECRET = "a-secret-of-at-least-thirty-two-characters";

    /** The queue in memory. */
    static class MemoryQueue implements SandboxProvisioningPort {
        final Map<String, Entry> entries = new LinkedHashMap<>();
        boolean broken;

        public void enqueue(String tenantId, String owner, String plan, Instant now) {
            if (broken) throw new IllegalStateException("database unavailable");
            entries.putIfAbsent(tenantId, new Entry(tenantId, owner, "Ana García", plan, State.PENDING, 0, null, now));
        }
        public List<Entry> due(Instant now, int limit) {
            return entries.values().stream().filter(e -> e.state() == State.PENDING && !e.nextAttempt().isAfter(now))
                    .limit(limit).toList();
        }
        public Optional<Entry> find(String tenantId) { return Optional.ofNullable(entries.get(tenantId)); }
        public void done(String tenantId, Instant now) {
            entries.computeIfPresent(tenantId, (k, e) -> new Entry(e.tenantId(), e.ownerUsername(), e.ownerName(), e.plan(),
                    State.DONE, e.attempts(), null, e.nextAttempt()));
        }
        public void failed(String tenantId, String error, Instant next) {
            entries.computeIfPresent(tenantId, (k, e) -> new Entry(e.tenantId(), e.ownerUsername(), e.ownerName(), e.plan(),
                    e.state(), e.attempts() + 1, error, next));
        }
    }

    /** A sandbox that answers, or is down. */
    static class FakeSandbox implements SandboxLinkPort {
        final List<LinkedLocation> received = new ArrayList<>();
        boolean down;

        public void provision(LinkedLocation location) {
            if (down) throw new Unavailable("The sandbox could not be reached");
            received.add(location);
        }
    }

    /** A clock the test moves forward. */
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-12T12:00:00Z");
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    final MemoryQueue queue = new MemoryQueue();
    final FakeSandbox sandbox = new FakeSandbox();
    final TestClock clock = new TestClock();
    final PlatformEnvironment production = new PlatformEnvironment("production");

    SandboxProvisioningService linkedProduction() {
        return new SandboxProvisioningService(queue, sandbox, production,
                new PlatformLink(production, "http://sandbox", "", SECRET, false), clock);
    }

    @Test
    void aNewLocationIsListedAndSentToTheSandbox() {
        var service = linkedProduction();

        service.locationCreated("kebab", "ana", "RESTAURANT");
        assertThat(sandbox.received).as("nothing is sent while the location is being created").isEmpty();
        assertThat(service.sendDue()).isEqualTo(1);

        assertThat(sandbox.received).containsExactly(
                new SandboxLinkPort.LinkedLocation("kebab", "ana", "Ana García", "RESTAURANT"));
        assertThat(service.status("kebab")).get().extracting(SandboxProvisioningPort.Entry::state)
                .isEqualTo(SandboxProvisioningPort.State.DONE);
        assertThat(service.sendDue()).as("sent once").isZero();
    }

    @Test
    void aSandboxThatIsDownIsTriedAgainLaterAndNothingFails() {
        var service = linkedProduction();
        sandbox.down = true;
        service.locationCreated("kebab", "ana", "RESTAURANT");

        assertThat(service.sendDue()).isZero();
        var entry = service.status("kebab").orElseThrow();
        assertThat(entry.attempts()).isEqualTo(1);
        assertThat(entry.lastError()).contains("could not be reached");
        assertThat(entry.nextAttempt()).isEqualTo(clock.now.plusSeconds(30));
        assertThat(service.sendDue()).as("not before it is due").isZero();

        sandbox.down = false;
        clock.now = clock.now.plusSeconds(31);
        assertThat(service.sendDue()).isEqualTo(1);
    }

    @Test
    void retriesWaitLongerEachTimeUpToHalfAnHour() {
        assertThat(SandboxProvisioningService.wait(0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(SandboxProvisioningService.wait(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(SandboxProvisioningService.wait(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(SandboxProvisioningService.wait(40)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void anOwnerAboutToEnterGetsTheLocationRightAway() {
        var service = linkedProduction();
        service.locationCreated("kebab", "ana", "RESTAURANT");

        assertThat(service.ensure("kebab", "ana")).isTrue();
        assertThat(sandbox.received).hasSize(1);
        assertThat(service.ensure("kebab", "ana")).as("already there").isTrue();
        assertThat(sandbox.received).hasSize(1);
    }

    @Test
    void aLocationFromBeforeTheLinkIsListedWhenItsOwnerEnters() {
        var service = linkedProduction();

        assertThat(service.ensure("oldcafe", "ana")).isTrue();
        assertThat(sandbox.received).extracting(SandboxLinkPort.LinkedLocation::tenantId).containsExactly("oldcafe");

        sandbox.down = true;
        assertThat(service.ensure("oldbistro", "ana")).as("still being prepared").isFalse();
        assertThat(service.status("oldbistro")).get().extracting(SandboxProvisioningPort.Entry::attempts).isEqualTo(1);
    }

    @Test
    void creatingTheLocationNeverFailsBecauseOfTheList() {
        queue.broken = true;

        linkedProduction().locationCreated("kebab", "ana", "RESTAURANT");

        assertThat(queue.entries).isEmpty();
    }

    @Test
    void onlyALinkedProductionSetsLocationsUpInASandbox() {
        var standalone = new SandboxProvisioningService(queue, sandbox, production,
                new PlatformLink(production, "", "", "", true), clock);
        var sandboxItself = new SandboxProvisioningService(queue, sandbox, new PlatformEnvironment("sandbox"),
                new PlatformLink(new PlatformEnvironment("sandbox"), "http://production", "", SECRET, false), clock);

        standalone.locationCreated("kebab", "ana", "RESTAURANT");
        sandboxItself.locationCreated("cafe", "ana", "FREE");

        assertThat(queue.entries).isEmpty();
        assertThat(standalone.sendDue()).isZero();
        assertThat(standalone.status("kebab")).isEmpty();
    }
}
