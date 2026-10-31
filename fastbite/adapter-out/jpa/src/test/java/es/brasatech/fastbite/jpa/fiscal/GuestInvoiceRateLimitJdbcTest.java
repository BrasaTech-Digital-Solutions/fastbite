package es.brasatech.fastbite.jpa.fiscal;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;

class GuestInvoiceRateLimitJdbcTest {
    private JdbcDataSource source;
    private JdbcTemplate jdbc;
    private GuestInvoiceRateLimitJdbc limit;
    private final Instant now = Instant.parse("2026-10-31T10:00:00Z");

    @BeforeEach void setup() {
        source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:invoice" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        source.setUser("sa");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE public.signup_reservation_lock (id INT PRIMARY KEY)");
        jdbc.execute("INSERT INTO public.signup_reservation_lock VALUES (1)");
        jdbc.execute("CREATE TABLE public.account_email_limits (id VARCHAR(36) PRIMARY KEY, subject_key VARCHAR(512), purpose VARCHAR(32), sent_at BIGINT)");
        limit = new GuestInvoiceRateLimitJdbc(source, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test void concurrentAttemptsCannotExceedTicketQuotaAcrossInstances() throws Exception {
        try (var pool = Executors.newFixedThreadPool(12)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i = 0; i < 20; i++) {
                String client = "hashed-client-" + i;
                tasks.add(() -> new GuestInvoiceRateLimitJdbc(source, Clock.fixed(now, ZoneOffset.UTC)).tryAcquire("hashed-ticket", client));
            }
            var results = pool.invokeAll(tasks);
            int accepted = 0;
            for (var result : results) if (result.get()) accepted++;
            assertThat(accepted).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.account_email_limits", Integer.class)).isEqualTo(10);
        }
    }

    @Test void clientQuotaAndHourlyExpiryAreEnforced() {
        for (int i = 0; i < 10; i++) assertThat(limit.tryAcquire("hashed-ticket-" + i, "hashed-client")).isTrue();
        assertThat(limit.tryAcquire("another-ticket", "hashed-client")).isFalse();
        var later = new GuestInvoiceRateLimitJdbc(source, Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC));
        assertThat(later.tryAcquire("another-ticket", "hashed-client")).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.account_email_limits", Integer.class)).isEqualTo(2);
    }
}
