package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.mail.AccountMailOutboxPort;
import es.brasatech.fastbite.application.verifactu.VerifactuNotificationService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountMailOutboxJdbcVerifactuLatchTest {

    private static final Instant T0 = Instant.parse("2026-11-21T10:00:00Z");

    private JdbcTemplate jdbc;
    private AccountMailOutboxJdbcAdapter adapter;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:vf_latch_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        new TenantMigrationService(ds).migrateAll(false);
        this.jdbc = new JdbcTemplate(ds);
        this.adapter = new AccountMailOutboxJdbcAdapter(ds);

        jdbc.update("INSERT INTO public.users (id, username, password, active, tenant_id, email, email_verified_at, account_language) VALUES ('u1', 'ana', 'hash', TRUE, 'lacasita', 'ana@example.test', ?, 'es')",
                T0.toEpochMilli());
        jdbc.update("INSERT INTO public.tenant_locations (id, owner_username, tenant_id, plan) VALUES ('l1', 'ana', 'lacasita', 'RESTAURANT')");
    }

    @Test
    void latchesOnHoldOnceUpgradesReopenToVerifactuReopenedAndAllowsFutureCycle() {
        // First opening (not previously on hold) keeps VERIFACTU_OPENED_TRIAL and latches opened
        assertThat(adapter.enqueueVerifactuNotification("lacasita", VerifactuNotificationService.PURPOSE_OPENED_TRIAL, T0))
                .isTrue();
        assertThat(adapter.enqueueVerifactuNotification("lacasita", VerifactuNotificationService.PURPOSE_OPENED_TRIAL, T0.plusSeconds(10)))
                .isFalse();

        // Transition to ON_HOLD clears opened latch and enqueues ON_HOLD once
        assertThat(adapter.enqueueVerifactuNotification("lacasita", VerifactuNotificationService.PURPOSE_ON_HOLD, T0.plusSeconds(60)))
                .isTrue();
        assertThat(adapter.enqueueVerifactuNotification("lacasita", VerifactuNotificationService.PURPOSE_ON_HOLD, T0.plusSeconds(70)))
                .isFalse();

        // Transition back to open after hold automatically becomes VERIFACTU_REOPENED (no trial text)
        assertThat(adapter.enqueueVerifactuNotification("lacasita", VerifactuNotificationService.PURPOSE_OPENED_FREE, T0.plusSeconds(120)))
                .isTrue();
        assertThat(adapter.enqueueVerifactuNotification("lacasita", VerifactuNotificationService.PURPOSE_OPENED_FREE, T0.plusSeconds(130)))
                .isFalse();

        // Second hold fires again because the reopened transition cleared verifactu_hold_notified_at
        assertThat(adapter.enqueueVerifactuNotification("lacasita", VerifactuNotificationService.PURPOSE_ON_HOLD, T0.plusSeconds(180)))
                .isTrue();

        List<String> purposes = jdbc.queryForList(
                "SELECT purpose FROM public.account_email_outbox WHERE subject_id = 'lacasita' ORDER BY created_at",
                String.class);
        assertThat(purposes).containsExactly(
                VerifactuNotificationService.PURPOSE_OPENED_TRIAL,
                VerifactuNotificationService.PURPOSE_ON_HOLD,
                VerifactuNotificationService.PURPOSE_REOPENED,
                VerifactuNotificationService.PURPOSE_ON_HOLD);

        // Claim + issue resolves the verified owner email and language
        var claim = adapter.claimForSubject("lacasita", T0.plusSeconds(200));
        assertThat(claim).isPresent();
        var recipient = adapter.issue(claim.get(), "unused-hash", T0.plusSeconds(200));
        assertThat(recipient).contains(new AccountMailOutboxPort.Recipient("ana@example.test", "ana", "es"));
    }
}
