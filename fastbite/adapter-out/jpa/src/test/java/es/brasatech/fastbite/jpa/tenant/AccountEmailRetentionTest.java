package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.mail.AccountMailOutboxService;
import es.brasatech.fastbite.application.mail.OwnerSetupMailPort;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AccountEmailRetentionTest {
    private final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    private final long old = now.minus(Duration.ofDays(30)).minusMillis(1).toEpochMilli();
    private final long boundary = now.minus(Duration.ofDays(30)).toEpochMilli();
    private JdbcTemplate jdbc;
    private AccountMailOutboxService service;
    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:retention" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        source.setUser("sa");
        new TenantMigrationService(source).migrateAll(false);
        jdbc = new JdbcTemplate(source);
        service = new AccountMailOutboxService(new AccountMailOutboxJdbcAdapter(source), mock(OwnerSetupMailPort.class),
                new PlatformEnvironment("production"), Clock.fixed(now, ZoneOffset.UTC), "http://localhost:8080");
    }
    @Test void deletesOnlyTokensConsumedOrExpiredMoreThanThirtyDaysAgo() {
        token("consumed", old, now.plusSeconds(10).toEpochMilli());
        token("expired", null, old);
        token("boundary", null, boundary);
        token("active", null, now.plusSeconds(10).toEpochMilli());
        assertThat(service.cleanup().tokens()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT token_hash FROM public.account_tokens ORDER BY token_hash", String.class)).containsExactly("active", "boundary");
    }
    @Test void deletesLimitRecordsOlderThanThirtyDays() {
        limit("old", old); limit("boundary", boundary);
        assertThat(service.cleanup().limits()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM public.account_email_limits", String.class)).containsExactly("boundary");
    }
    @Test void deletesOnlyFinishedOutboxRecordsAfterRetention() {
        outbox("sent", "SENT", old, old); outbox("failed", "FAILED", old, old);
        outbox("cancelled", "CANCELLED", old, old); outbox("boundary", "SENT", old, boundary);
        // Legacy terminal rows with unknown finish time get a fresh retention window.
        outbox("legacy", "FAILED", old, null);
        assertThat(service.cleanup().outbox()).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT id FROM public.account_email_outbox ORDER BY id", String.class)).containsExactly("boundary", "legacy");
        assertThat(jdbc.queryForObject("SELECT finished_at FROM public.account_email_outbox WHERE id='legacy'", Long.class)).isEqualTo(now.toEpochMilli());
    }
    @Test void pendingAndClaimedRecordsSurviveRegardlessOfAge() {
        outbox("pending", "PENDING", old, old); outbox("claimed", "CLAIMED", old, old);
        assertThat(service.cleanup().outbox()).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM public.account_email_outbox ORDER BY id", String.class)).containsExactly("claimed", "pending");
    }
    private void token(String id, Long consumed, long expires) {
        jdbc.update("INSERT INTO public.account_tokens (token_hash,purpose,subject_type,subject_id,created_at,expires_at,consumed_at) VALUES (?, 'PASSWORD_RESET','OWNER','test',0,?,?)", id, expires, consumed);
    }
    private void limit(String id, long time) {
        jdbc.update("INSERT INTO public.account_email_limits (id,subject_key,purpose,sent_at) VALUES (?, 'test','PASSWORD_RESET',?)", id, time);
    }
    private void outbox(String id, String state, long created, Long finished) {
        jdbc.update("INSERT INTO public.account_email_outbox (id,purpose,subject_type,subject_id,language,state,attempts,available_at,created_at,finished_at) VALUES (?, 'PASSWORD_RESET','OWNER','test','en',?,0,?,?,?)", id, state, created, created, finished);
    }
}
