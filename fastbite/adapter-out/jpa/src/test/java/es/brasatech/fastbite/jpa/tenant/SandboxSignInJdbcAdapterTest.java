package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.jpa.TestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestConfig.class)
class SandboxSignInJdbcAdapterTest {

    @Autowired SandboxSignInJdbcAdapter signIns;
    @Autowired JdbcTemplate jdbc;

    static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private static String nonce() {
        return UUID.randomUUID().toString();
    }

    @Test
    void aLinkOpensTheSandboxOnlyOnce() {
        String nonce = nonce();

        assertThat(signIns.claim(nonce, NOW.plusSeconds(60), NOW)).isTrue();
        assertThat(signIns.claim(nonce, NOW.plusSeconds(60), NOW.plusSeconds(1))).isFalse();
    }

    @Test
    void expiredLinksAreForgotten() {
        String expired = nonce();
        signIns.claim(expired, NOW.plusSeconds(60), NOW);

        signIns.claim(nonce(), NOW.plusSeconds(120), NOW.plusSeconds(60));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.sandbox_sign_in WHERE nonce = ?", Integer.class, expired))
                .isZero();
    }
}
