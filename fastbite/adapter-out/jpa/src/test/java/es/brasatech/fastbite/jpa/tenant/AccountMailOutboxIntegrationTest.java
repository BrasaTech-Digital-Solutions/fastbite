package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.OwnerSetupPort;
import es.brasatech.fastbite.jpa.TestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Runs outside a surrounding test transaction, as the request and scheduler do. */
@SpringBootTest(classes = TestConfig.class)
class AccountMailOutboxIntegrationTest {
    @Autowired OwnerSetupJdbcAdapter setup;
    @Autowired TenantProvisionerAdapter provisioner;
    @Autowired AccountMailOutboxJdbcAdapter outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;

    @Test void queuesAtomicallyThenIssuesAndRetriesWithFreshHashes() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String tenant = "outbox" + suffix, checkout = "checkout" + suffix;
        provisioner.provisionTenant(tenant);
        var invitation = new OwnerSetupPort.Invitation(checkout, tenant, "owner" + suffix, "Owner", "owner@example.test", "RESTAURANT");
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(setup.prepareQueued(invitation, now)).isTrue();
        assertThat(setup.prepareQueued(invitation, now)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.account_email_outbox WHERE subject_id = ?", Integer.class, checkout)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM public.owner_setup_tokens WHERE checkout_id = ?", String.class, checkout)).isNull();
        var claim = outbox.claimForSubject(checkout, now).orElseThrow();
        assertThat(outbox.issueOwnerSetup(claim, "oldhash", now)).isPresent();
        assertThat(setup.isValid("oldhash", now)).isTrue();
        outbox.failed(claim, now);
        assertThat(outbox.claimForSubject(checkout, now.plusSeconds(59))).isEmpty();
        var retry = outbox.claimForSubject(checkout, now.plusSeconds(60)).orElseThrow();
        assertThat(outbox.issueOwnerSetup(retry, "newhash", now.plusSeconds(60))).isPresent();
        assertThat(setup.isValid("oldhash", now)).isFalse();
        assertThat(setup.isValid("newhash", now)).isTrue();
        outbox.sent(retry, now.plusSeconds(60));
        assertThat(outbox.claimForSubject(checkout, now.plusSeconds(61))).isEmpty();
    }
    @Test void rateLimitsAndLeaseRecoveryAreEnforced() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String tenant = "limit" + suffix, checkout = "limit" + suffix;
        provisioner.provisionTenant(tenant);
        var invitation = new OwnerSetupPort.Invitation(checkout, tenant, "owner" + suffix, "Owner", "owner@example.test", "RESTAURANT");
        Instant now = Instant.parse("2026-02-01T00:00:00Z");
        setup.prepareQueued(invitation, now);
        var abandoned = outbox.claimForSubject(checkout, now).orElseThrow();
        assertThat(outbox.claimForSubject(checkout, now.plusSeconds(119))).isEmpty();
        var recovered = outbox.claimForSubject(checkout, now.plusSeconds(120)).orElseThrow();
        assertThat(outbox.issueOwnerSetup(abandoned, "stale", now.plusSeconds(120))).isEmpty();
        assertThat(outbox.issueOwnerSetup(recovered, "first", now.plusSeconds(120))).isPresent();
        outbox.sent(recovered, now.plusSeconds(120));
        for (int i = 1; i < 5; i++) {
            Instant current = now.plusSeconds(120 + 60L * i);
            setup.prepareQueued(invitation, current);
            var claim = outbox.claimForSubject(checkout, current).orElseThrow();
            assertThat(outbox.issueOwnerSetup(claim, "hash" + i, current)).isPresent();
            outbox.sent(claim, current);
        }
        Instant sixth = now.plusSeconds(420);
        setup.prepareQueued(invitation, sixth);
        var limited = outbox.claimForSubject(checkout, sixth).orElseThrow();
        assertThat(outbox.issueOwnerSetup(limited, "not-issued", sixth)).isEmpty();
        assertThat(outbox.claimForSubject(checkout, sixth.plusSeconds(60))).isEmpty();
        var later = outbox.claimForSubject(checkout, now.plusSeconds(120 + 86401)).orElseThrow();
        assertThat(outbox.issueOwnerSetup(later, "after-window", now.plusSeconds(120 + 86401))).isPresent();
        outbox.sent(later, now.plusSeconds(120 + 86401));
    }
    @Test void claimSkipsAnAlreadyLockedRow() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String firstTenant = "first" + suffix, nextTenant = "next" + suffix;
        String firstCheckout = "first" + suffix, nextCheckout = "next" + suffix;
        provisioner.provisionTenant(firstTenant);
        provisioner.provisionTenant(nextTenant);
        Instant now = Instant.parse("1970-02-01T00:00:01Z");
        setup.prepareQueued(new OwnerSetupPort.Invitation(firstCheckout, firstTenant, "a" + suffix, "A", "a@example.test", "RESTAURANT"), now.minusMillis(1));
        setup.prepareQueued(new OwnerSetupPort.Invitation(nextCheckout, nextTenant, "b" + suffix, "B", "b@example.test", "RESTAURANT"), now);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT id FROM public.account_email_outbox WHERE subject_id = ? FOR UPDATE")) {
                lock.setString(1, firstCheckout);
                try (var rows = lock.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    var claim = outbox.claim(now).orElseThrow();
                    assertThat(claim.subjectId()).isEqualTo(nextCheckout);
                    outbox.sent(claim, now);
                }
            } finally { connection.rollback(); }
        }
        var first = outbox.claimForSubject(firstCheckout, now).orElseThrow();
        outbox.sent(first, now);
    }
}
