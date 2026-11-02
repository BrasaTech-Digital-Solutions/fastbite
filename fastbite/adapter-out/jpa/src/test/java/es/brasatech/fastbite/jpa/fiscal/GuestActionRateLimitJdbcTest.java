package es.brasatech.fastbite.jpa.fiscal;

import es.brasatech.fastbite.application.fiscal.GuestActionRateLimit.Action;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuestActionRateLimitJdbcTest {
    private JdbcDataSource source;
    private JdbcTemplate jdbc;
    private GuestActionRateLimitJdbc limit;
    private final Instant now = Instant.parse("2026-10-31T10:00:00Z");

    @BeforeEach void setup() {
        source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:invoice" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        source.setUser("sa");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE public.signup_reservation_lock (id INT PRIMARY KEY)");
        jdbc.execute("INSERT INTO public.signup_reservation_lock VALUES (1)");
        jdbc.execute("CREATE TABLE public.account_email_limits (id VARCHAR(36) PRIMARY KEY, subject_key VARCHAR(512), purpose VARCHAR(32), sent_at BIGINT)");
        jdbc.update("INSERT INTO public.account_email_limits VALUES ('a', 'hash', 'GUEST_INVOICE_TICKET', 1)");
        jdbc.update("INSERT INTO public.account_email_limits VALUES ('b', 'hash', 'GUEST_INVOICE_CLIENT', 1)");
        jdbc.update("INSERT INTO public.account_email_limits VALUES ('c', 'hash', 'OWNER_SETUP', 1)");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/platform/V35__Guest_action_limits.sql"));
        } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
        limit = new GuestActionRateLimitJdbc(source, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test void concurrentAttemptsCannotExceedTicketQuotaAcrossInstances() throws Exception {
        try (var pool = Executors.newFixedThreadPool(12)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i = 0; i < 20; i++) {
                String client = "hashed-client-" + i;
                tasks.add(() -> new GuestActionRateLimitJdbc(source, Clock.fixed(now, ZoneOffset.UTC)).tryAcquire("hashed-ticket", client));
            }
            var results = pool.invokeAll(tasks);
            int accepted = 0;
            for (var result : results) if (result.get()) accepted++;
            assertThat(accepted).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.guest_action_limits", Integer.class)).isEqualTo(10);
        }
    }

    @Test void clientQuotaAndHourlyExpiryAreEnforced() {
        for (int i = 0; i < 10; i++) assertThat(limit.tryAcquire("hashed-ticket-" + i, "hashed-client")).isTrue();
        assertThat(limit.tryAcquire("another-ticket", "hashed-client")).isFalse();
        var later = new GuestActionRateLimitJdbc(source, Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC));
        assertThat(later.tryAcquire("another-ticket", "hashed-client")).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.guest_action_limits", Integer.class)).isEqualTo(2);
    }
    @Test void migrationRemovesOnlyObsoleteGuestRows() {
        assertThat(jdbc.queryForList("SELECT purpose FROM public.account_email_limits", String.class)).containsExactly("OWNER_SETUP");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.signup_reservation_lock", Integer.class)).isEqualTo(1);
    }

    @Test void distinctPurposesAndDifferentWindowsAreIndependent() {
        for (int i = 0; i < 5; i++) assertThat(limit.tryAcquire("ticket", "invoice-client-" + i)).isTrue();
        assertThat(limit.tryAcquire("ticket", "invoice-client-x")).isFalse();
        for (int i = 0; i < 3; i++) assertThat(limit.tryAcquire(Action.EMAIL, "ticket", "email-client-" + i)).isTrue();
        assertThat(limit.tryAcquire(Action.EMAIL, "ticket", "email-client-x")).isFalse();
        var afterHour = new GuestActionRateLimitJdbc(source, Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC));
        assertThat(afterHour.tryAcquire("ticket", "invoice-client-x")).isTrue();
        assertThat(afterHour.tryAcquire(Action.EMAIL, "ticket", "email-client-x")).isFalse();
        var afterDay = new GuestActionRateLimitJdbc(source, Clock.fixed(now.plusSeconds(86400), ZoneOffset.UTC));
        assertThat(afterDay.tryAcquire(Action.EMAIL, "ticket", "email-client-x")).isTrue();
    }

    @Test void failedSecondInsertRollsBackBothAttempts() {
        jdbc.execute("ALTER TABLE public.guest_action_limits ADD CONSTRAINT client_rejected CHECK (purpose <> 'GUEST_INVOICE_CLIENT')");
        assertThatThrownBy(() -> limit.tryAcquire("ticket", "client")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.guest_action_limits", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.account_email_limits", Integer.class)).isEqualTo(1);
    }

    @Test void guestActionsDoNotWaitForSignupLock() throws Exception {
        try (var connection = source.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM public.signup_reservation_lock WHERE id = 1 FOR UPDATE");
                 var ignored = statement.executeQuery()) {
                assertThat(pool.submit(() -> limit.tryAcquire("ticket", "client")).get(3, TimeUnit.SECONDS)).isTrue();
            } finally { connection.rollback(); }
        }
    }

}
