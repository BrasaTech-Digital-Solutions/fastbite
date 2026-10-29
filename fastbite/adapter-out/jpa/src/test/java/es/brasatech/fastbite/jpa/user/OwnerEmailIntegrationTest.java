package es.brasatech.fastbite.jpa.user;

import es.brasatech.fastbite.jpa.TestConfig;
import es.brasatech.fastbite.jpa.tenant.AccountMailOutboxJdbcAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestConfig.class)
class OwnerEmailIntegrationTest {
    @Autowired OwnerEmailJdbcAdapter owners;
    @Autowired AccountMailOutboxJdbcAdapter outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired es.brasatech.fastbite.application.tenant.PendingSignupService signup;
    @Autowired es.brasatech.fastbite.application.mail.AccountMailOutboxService mail;
    @Autowired java.time.Clock clock;
    @Test void verificationChangesOnlyAfterLinkAndReplayRemainsValid() {
        String id = UUID.randomUUID().toString(), username = "owner" + id;
        Instant now = Instant.now();
        jdbc.update("INSERT INTO public.users (id, username, password, active, email, email_verified_at) VALUES (?, ?, 'hash', TRUE, ?, ?)", id, username, "old" + id + "@example.test", now.toEpochMilli());
        String email = "new" + id + "@example.test";
        assertThat(owners.request(username, email, "en", now)).isTrue();
        assertThat(owners.status(username).orElseThrow().email()).startsWith("old");
        var claim = outbox.claimForSubject(id, now).orElseThrow();
        assertThat(outbox.issue(claim, "hash-" + id, now)).isPresent();
        outbox.sent(claim, now);
        assertThat(owners.verify("hash-" + id, now)).contains(id);
        assertThat(owners.verify("hash-" + id, now)).contains(id);
        assertThat(owners.status(username).orElseThrow().email()).isEqualTo(email);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.account_email_outbox WHERE subject_id = ? AND purpose = 'OWNER_EMAIL_NOTICE'", Integer.class, id)).isEqualTo(1);
    }
    @Test void replacementInvalidatesOldLinkAndExpiredLinkIsRejected() {
        String id = UUID.randomUUID().toString(), username = "owner" + id;
        Instant now = Instant.now();
        jdbc.update("INSERT INTO public.users (id, username, password, active) VALUES (?, ?, 'hash', TRUE)", id, username);
        assertThat(owners.request(username, "first" + id + "@example.test", "en", now)).isTrue();
        var claim = outbox.claimForSubject(id, now).orElseThrow();
        assertThat(outbox.issue(claim, "first-" + id, now)).isPresent(); outbox.sent(claim, now);
        assertThat(owners.request(username, "second" + id + "@example.test", "en", now.plusSeconds(61))).isTrue();
        assertThat(owners.verify("first-" + id, now.plusSeconds(61))).isEmpty();
        var second = outbox.claimForSubject(id, now.plusSeconds(61)).orElseThrow();
        assertThat(outbox.issue(second, "second-" + id, now.plusSeconds(61))).isPresent(); outbox.sent(second, now.plusSeconds(61));
        assertThat(owners.verify("second-" + id, now.plusSeconds(61 + 48 * 3600))).isEmpty();
        assertThat(owners.status(username).orElseThrow().verified()).isFalse();
    }
    @Test void resendIsRateLimitedPerOwner() {
        String id = UUID.randomUUID().toString(), username = "owner" + id;
        Instant now = Instant.now();
        jdbc.update("INSERT INTO public.users (id, username, password, active) VALUES (?, ?, 'hash', TRUE)", id, username);
        owners.request(username, "rate" + id + "@example.test", "en", now);
        var first = outbox.claimForSubject(id, now).orElseThrow();
        assertThat(outbox.issue(first, "rate-first-" + id, now)).isPresent(); outbox.sent(first, now);
        owners.resend(username, now.plusSeconds(1));
        var second = outbox.claimForSubject(id, now.plusSeconds(1)).orElseThrow();
        assertThat(outbox.issue(second, "rate-second-" + id, now.plusSeconds(1))).isEmpty();
        assertThat(outbox.claimForSubject(id, now.plusSeconds(59))).isEmpty();
        var due = outbox.claimForSubject(id, now.plusSeconds(60)).orElseThrow();
        assertThat(outbox.issue(due, "rate-due-" + id, now.plusSeconds(60))).isPresent();
    }
    @Test void atMostFiveVerificationEmailsPerRollingDay() {
        String id = UUID.randomUUID().toString(), username = "owner" + id;
        Instant now = Instant.now();
        jdbc.update("INSERT INTO public.users (id, username, password, active) VALUES (?, ?, 'hash', TRUE)", id, username);
        owners.request(username, "daily" + id + "@example.test", "en", now);
        for (int i = 0; i < 5; i++) {
            Instant when = now.plusSeconds(i * 61L);
            if (i > 0) owners.resend(username, when);
            var claim = outbox.claimForSubject(id, when).orElseThrow();
            assertThat(outbox.issue(claim, "daily-" + i + "-" + id, when)).isPresent();
            outbox.sent(claim, when);
        }
        Instant sixth = now.plusSeconds(5 * 61L);
        owners.resend(username, sixth);
        var claim = outbox.claimForSubject(id, sixth).orElseThrow();
        assertThat(outbox.issue(claim, "daily-sixth-" + id, sixth)).isEmpty();
        assertThat(outbox.claimForSubject(id, now.plusSeconds(24 * 3600))).isEmpty();
        assertThat(outbox.claimForSubject(id, now.plusSeconds(24 * 3600 + 1))).isPresent();
    }
    @Test void serviceRequestsMailAfterTheDatabaseTransaction() throws Exception {
        String id = UUID.randomUUID().toString(), username = "owner" + id;
        jdbc.update("INSERT INTO public.users (id, username, password, active) VALUES (?, ?, 'hash', TRUE)", id, username);
        var service = new es.brasatech.fastbite.application.office.OwnerEmailService(owners, signup, mail, clock);
        service.request(username, "service" + id + "@example.test", "en");
        org.mockito.Mockito.verify(mail).runForSignup(id);
        assertThat(owners.status(username).orElseThrow().pendingEmail()).isEqualTo("service" + id + "@example.test");
    }
}
