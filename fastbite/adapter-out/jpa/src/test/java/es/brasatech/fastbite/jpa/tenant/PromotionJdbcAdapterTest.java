package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.platform.PromotionPort;
import es.brasatech.fastbite.application.platform.PromotionPort.Entry;
import es.brasatech.fastbite.application.platform.PromotionPort.State;
import es.brasatech.fastbite.jpa.TestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestConfig.class)
class PromotionJdbcAdapterTest {

    @Autowired
    private PromotionJdbcAdapter adapter;

    static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");

    private static String tenant() {
        return "promo" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void leaseClaimAndReleaseLifecycle() {
        String tenant = tenant();
        Duration lease = Duration.ofMinutes(10);

        OptionalLong claim1 = adapter.claimLease(tenant, NOW, lease);
        assertThat(claim1).isPresent();
        assertThat(adapter.claimLease(tenant, NOW.plusSeconds(30), lease)).isEmpty();

        // Release lease with own claim
        adapter.releaseLease(tenant, claim1.getAsLong());
        OptionalLong claim2 = adapter.claimLease(tenant, NOW.plusSeconds(35), lease);
        assertThat(claim2).isPresent();

        // Expire lease
        Instant future = NOW.plusSeconds(35).plus(Duration.ofMinutes(11));
        OptionalLong claim3 = adapter.claimLease(tenant, future, lease);
        assertThat(claim3).isPresent();

        // Releasing with stale claim1 must NOT free claim3
        adapter.releaseLease(tenant, claim1.getAsLong());
        assertThat(adapter.claimLease(tenant, future.plusSeconds(10), lease)).isEmpty();

        // Release with valid claim3 frees it
        adapter.releaseLease(tenant, claim3.getAsLong());
        assertThat(adapter.claimLease(tenant, future.plusSeconds(15), lease)).isPresent();
    }

    @Test
    void concurrentLeaseClaimsOnEmptyTableYieldsExactlyOneWinnerWithoutThrowing() throws Exception {
        String tenant = tenant();
        Duration lease = Duration.ofMinutes(10);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<OptionalLong> task = () -> {
            barrier.await();
            return adapter.claimLease(tenant, NOW, lease);
        };

        Future<OptionalLong> f1 = executor.submit(task);
        Future<OptionalLong> f2 = executor.submit(task);

        OptionalLong r1 = f1.get(5, TimeUnit.SECONDS);
        OptionalLong r2 = f2.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(r1.isPresent() ^ r2.isPresent()).isTrue();
    }

    @Test
    void savePreviewAndConsumeTokenAtomically() {
        String tenant = tenant();
        String tokenHash = "hash-" + UUID.randomUUID();
        Entry entry = new Entry(
                UUID.randomUUID().toString(),
                tenant,
                "ana",
                State.PREVIEWED,
                tokenHash,
                "/storage/promotions/" + tenant + "/pending.zip",
                null,
                null,
                "summary",
                NOW,
                NOW.plus(Duration.ofMinutes(15)),
                null,
                null
        );
        adapter.savePreview(entry);

        Optional<Entry> found = adapter.findByTokenHash(tokenHash);
        assertThat(found).isPresent();
        assertThat(found.get().state()).isEqualTo(State.PREVIEWED);

        // First consume succeeds
        assertThat(adapter.consumeToken(tokenHash, tenant, "ana", NOW.plusSeconds(5))).isTrue();
        // Second consume fails (already APPLYING)
        assertThat(adapter.consumeToken(tokenHash, tenant, "ana", NOW.plusSeconds(6))).isFalse();

        Optional<Entry> updated = adapter.findByTokenHash(tokenHash);
        assertThat(updated).isPresent();
        assertThat(updated.get().state()).isEqualTo(State.APPLYING);
    }

    @Test
    void latestAppliedOrUndoneFindsMostRecentRow() {
        String tenant = tenant();
        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();

        Entry e1 = new Entry(id1, tenant, "ana", State.PREVIEWED, "h1-" + id1, "b1", null, null, "s1",
                NOW, NOW.plus(Duration.ofMinutes(15)), null, null);
        Entry e2 = new Entry(id2, tenant, "ana", State.PREVIEWED, "h2-" + id2, "b2", null, null, "s2",
                NOW.plusSeconds(10), NOW.plus(Duration.ofMinutes(25)), null, null);

        adapter.savePreview(e1);
        adapter.savePreview(e2);

        adapter.markApplied(id1, "snap1.zip", "snap1", NOW.plusSeconds(5));
        adapter.markApplied(id2, "snap2.zip", "snap2", NOW.plusSeconds(15));

        Optional<Entry> latest = adapter.findLatestAppliedOrUndone(tenant);
        assertThat(latest).isPresent();
        assertThat(latest.get().id()).isEqualTo(id2);
        assertThat(latest.get().state()).isEqualTo(State.APPLIED);

        // Once undone, latest is still id2 (in state UNDONE), so undo does not revert id1
        adapter.markUndone(id2, NOW.plusSeconds(20));
        Optional<Entry> afterUndo = adapter.findLatestAppliedOrUndone(tenant);
        assertThat(afterUndo).isPresent();
        assertThat(afterUndo.get().id()).isEqualTo(id2);
        assertThat(afterUndo.get().state()).isEqualTo(State.UNDONE);
    }

    @Test
    void findAppliedOrUndoneBeyondThreeRetention() {
        String tenant = tenant();
        for (int i = 0; i < 5; i++) {
            String id = UUID.randomUUID().toString();
            Entry e = new Entry(id, tenant, "ana", State.PREVIEWED, "hash-" + id, "b" + i, null, null, "s" + i,
                NOW.plusSeconds(i * 10), NOW.plus(Duration.ofMinutes(15)), null, null);
            adapter.savePreview(e);
            adapter.markApplied(id, "snap" + i + ".zip", "snap" + i, NOW.plusSeconds(i * 10 + 5));
        }

        List<Entry> beyondThree = adapter.findAppliedOrUndoneBeyond(tenant, 3);
        assertThat(beyondThree).hasSize(2);
        // Deleting old row removes it
        adapter.deleteRow(beyondThree.get(0).id());
        assertThat(adapter.findAppliedOrUndoneBeyond(tenant, 3)).hasSize(1);
    }
}
