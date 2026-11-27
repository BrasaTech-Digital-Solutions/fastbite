package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.platform.PendingSandboxDeletionPort;
import es.brasatech.fastbite.application.tenant.LocationNameHoldPort;
import es.brasatech.fastbite.application.tenant.PendingSignupPort;
import es.brasatech.fastbite.application.tenant.TenantLifecyclePort;
import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocationDeleteSafetyJdbcTest {

    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");

    private JdbcDataSource source;
    private JdbcTemplate jdbc;
    private LocationNameHoldJdbcAdapter nameHolds;
    private PendingSandboxDeletionJdbcAdapter pendingSync;
    private TenantLifecycleJdbcAdapter lifecycle;
    private PendingSignupJdbcAdapter pendingSignup;

    @BeforeEach
    void setUp() {
        source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:del_safety_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        source.setUser("sa");
        jdbc = new JdbcTemplate(source);
        new TenantMigrationService(source).migrateAll(false);
        nameHolds = new LocationNameHoldJdbcAdapter(source);
        pendingSync = new PendingSandboxDeletionJdbcAdapter(source);
        lifecycle = new TenantLifecycleJdbcAdapter(source);
        pendingSignup = new PendingSignupJdbcAdapter(source);
    }

    @Test
    void nameHoldRefusesOtherOwnerAndAllowsSameOwnerAndEndsHoldOnCreation() {
        Instant heldUntil = NOW.plus(90, ChronoUnit.DAYS);
        nameHolds.hold("bistro", "ana", heldUntil, NOW);

        assertThat(nameHolds.findActive("bistro", NOW))
                .isPresent()
                .get()
                .extracting(LocationNameHoldPort.NameHold::owner, LocationNameHoldPort.NameHold::heldUntil)
                .containsExactly("ana", heldUntil);

        // Another owner is refused on both PendingSignup and TenantLifecycle
        var strangerSignup = new PendingSignupPort.Signup(
                "bistro", "bob", "Bob", "$2a$10$hash", "RESTAURANT",
                "bob@example.com", "es", "27/10/2026", NOW, false);
        assertThatThrownBy(() -> pendingSignup.start(strangerSignup, NOW))
                .isInstanceOf(LocalizedRejection.class)
                .extracting(e -> ((LocalizedRejection) e).key())
                .isEqualTo("location.error.name_held");

        assertThatThrownBy(() -> lifecycle.register("bistro", "location:bistro", "bob",
                TenantLifecyclePort.State.ACTIVE, () -> {}))
                .isInstanceOf(LocalizedRejection.class)
                .extracting(e -> ((LocalizedRejection) e).key())
                .isEqualTo("location.error.name_held");

        // Same owner is allowed and ends the hold when registration finishes
        lifecycle.register("bistro", "location:bistro", "ana", TenantLifecyclePort.State.ACTIVE, () -> {});
        assertThat(nameHolds.findActive("bistro", NOW)).isEmpty();
    }

    @Test
    void expiredNameHoldIsIgnoredAndPurged() {
        Instant past = NOW.minus(1, ChronoUnit.SECONDS);
        nameHolds.hold("oldspot", "ana", past, NOW.minus(90, ChronoUnit.DAYS));

        assertThat(nameHolds.findActive("oldspot", NOW)).isEmpty();
        assertThat(nameHolds.purgeExpired(NOW)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.location_name_holds", Integer.class)).isZero();
    }

    @Test
    void pendingSandboxSyncPersistsHideRestoreAndEraseActions() {
        pendingSync.enqueue("bistro", "ana", PendingSandboxDeletionPort.Action.HIDE, "down", NOW, NOW);
        var hideRows = pendingSync.due(NOW, 10);
        assertThat(hideRows).hasSize(1);
        assertThat(hideRows.getFirst().action()).isEqualTo(PendingSandboxDeletionPort.Action.HIDE);

        // Superseding with RESTORE updates the same tenant_id row cleanly
        pendingSync.enqueue("bistro", "ana", PendingSandboxDeletionPort.Action.RESTORE, "still down", NOW, NOW);
        var restoreRows = pendingSync.due(NOW, 10);
        assertThat(restoreRows).hasSize(1);
        assertThat(restoreRows.getFirst().action()).isEqualTo(PendingSandboxDeletionPort.Action.RESTORE);

        // Superseding with ERASE updates the same tenant_id row cleanly
        pendingSync.enqueue("bistro", "ana", PendingSandboxDeletionPort.Action.ERASE, "timeout", NOW, NOW);
        var eraseRows = pendingSync.due(NOW, 10);
        assertThat(eraseRows).hasSize(1);
        assertThat(eraseRows.getFirst().action()).isEqualTo(PendingSandboxDeletionPort.Action.ERASE);

        pendingSync.markDone("bistro");
        assertThat(pendingSync.due(NOW, 10)).isEmpty();
    }
}
