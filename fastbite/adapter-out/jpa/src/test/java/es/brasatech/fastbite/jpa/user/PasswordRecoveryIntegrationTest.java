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
class PasswordRecoveryIntegrationTest {
    @Autowired PasswordRecoveryJdbcAdapter recovery;
    @Autowired AccountMailOutboxJdbcAdapter outbox;
    @Autowired JdbcTemplate jdbc;
    @Test void verifiedOwnerResetExpiresAndIsSingleUse() {
        String id = UUID.randomUUID().toString(), username = "owner" + id, email = "mail" + id + "@example.test";
        Instant now = Instant.now();
        jdbc.update("INSERT INTO public.users (id, username, password, active, email, email_verified_at) VALUES (?, ?, 'old', TRUE, ?, ?)", id, username, email, now.toEpochMilli());
        jdbc.update("INSERT INTO public.user_roles (user_id, role) VALUES (?, 'OWNER')", id);
        assertThat(recovery.enqueue(email.toUpperCase(java.util.Locale.ROOT), null, now)).isPresent();
        var claim = outbox.claimForSubject(id, now).orElseThrow();
        assertThat(outbox.issue(claim, "reset-" + id, now)).isPresent(); outbox.sent(claim, now);
        assertThat(recovery.valid("reset-" + id, now.plusSeconds(3600))).isFalse();
        assertThat(recovery.consume("reset-" + id, "new", now.plusSeconds(5))).isPresent();
        assertThat(recovery.consume("reset-" + id, "again", now.plusSeconds(5))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT password FROM public.users WHERE id = ?", String.class, id)).isEqualTo("new");
        assertThat(recovery.enqueue("missing@example.test", null, now)).isEmpty();
    }
    @Test void resetEmailsAreLimitedToFivePerDay() {
        String id = UUID.randomUUID().toString(), username = "limit" + id, email = username + "@example.test";
        Instant now = Instant.now();
        jdbc.update("INSERT INTO public.users (id, username, password, active, email, email_verified_at) VALUES (?, ?, 'old', TRUE, ?, ?)", id, username, email, now.toEpochMilli());
        jdbc.update("INSERT INTO public.user_roles (user_id, role) VALUES (?, 'OWNER')", id);
        for (int i = 0; i < 5; i++) {
            var when = now.plusSeconds(61L * i);
            assertThat(recovery.enqueue(username, null, when)).isPresent();
            var claim = outbox.claimForSubject(id, when).orElseThrow();
            assertThat(outbox.issue(claim, "reset-limit-" + i + id, when)).isPresent(); outbox.sent(claim, when);
        }
        var sixth = now.plusSeconds(61L * 5);
        recovery.enqueue(username, null, sixth);
        var claim = outbox.claimForSubject(id, sixth).orElseThrow();
        assertThat(outbox.issue(claim, "reset-limit-sixth-" + id, sixth)).isEmpty();
        assertThat(outbox.claimForSubject(id, now.plusSeconds(86400))).isEmpty();
    }
}
