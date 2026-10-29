package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.PendingSignupPort;
import es.brasatech.fastbite.application.tenant.PendingSignupService;
import es.brasatech.fastbite.jpa.TestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Tests use real JDBC transactions; the tests themselves have no surrounding transaction. */
@SpringBootTest(classes = TestConfig.class)
class PendingSignupIntegrationTest {
    @Autowired PendingSignupJdbcAdapter pending;
    @Autowired PendingSignupService service;
    @Autowired AccountMailOutboxJdbcAdapter outbox;
    @Autowired JdbcTemplate jdbc;

    private PendingSignupPort.Signup signup(String prefix, String email, Instant now) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return new PendingSignupPort.Signup(prefix + suffix, "u" + suffix, "Owner", "hash", "RESTAURANT", email,
                "en", "27/10/2026", now, true);
    }
    private String hash(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void pendingCreatesNoLocationAndReplacementAndExpiryFreeReservations() {
        Instant now = Instant.now();
        var first = signup("pa", "replace@example.test", now);
        var one = pending.start(first, now);
        assertThat(one.pending()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_locations WHERE tenant_id = ?", Integer.class, first.tenantId())).isZero();
        assertThat(pending.usernameAvailable(first.username(), now)).isFalse();
        var next = signup("pb", first.email(), now.plusSeconds(1));
        var two = pending.start(next, now.plusSeconds(1));
        assertThat(two.pending()).isTrue();
        assertThat(pending.isPending(one.pendingId(), now)).isFalse();
        assertThat(pending.usernameAvailable(first.username(), now)).isTrue();
        assertThat(pending.expire(now.plus(Duration.ofHours(49)))).isGreaterThanOrEqualTo(1);
        assertThat(pending.isPending(two.pendingId(), now.plus(Duration.ofHours(49)))).isFalse();
        assertThat(pending.usernameAvailable(next.username(), now.plus(Duration.ofHours(49)))).isTrue();
    }
    @Test void verificationCreatesEverythingAtomicallyAndIsIdempotent() throws Exception {
        Instant now = Instant.now();
        var input = signup("pc", "verify" + UUID.randomUUID() + "@example.test", now);
        var start = pending.start(input, now);
        var claim = outbox.claimForSubject(start.pendingId(), now).orElseThrow();
        String token = "a".repeat(43), tokenHash = hash(token);
        assertThat(outbox.issue(claim, tokenHash, now)).isPresent();
        outbox.sent(claim, now);
        assertThat(service.verify(token)).isTrue();
        assertThat(service.verify(token)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_locations WHERE tenant_id = ?", Integer.class, input.tenantId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT email FROM public.users WHERE username = ?", String.class, input.username())).isEqualTo(input.email());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.legal_acceptances WHERE username = ? AND document IN ('terms', 'marketing')", Integer.class, input.username())).isEqualTo(2);
        assertThat(pending.isPending(start.pendingId(), now)).isFalse();
    }
    @Test void verifiedAddressGetsOnlyANoticeAndNoReservation() {
        Instant now = Instant.now();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String email = "existing" + suffix + "@example.test";
        jdbc.update("INSERT INTO public.users (id, username, password, active, email, email_verified_at) VALUES (?, ?, 'hash', TRUE, ?, ?)", UUID.randomUUID().toString(), "existing" + suffix, email, now.toEpochMilli());
        var input = signup("pd", email, now);
        var result = pending.start(input, now);
        assertThat(result.pending()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.pending_registrations WHERE id = ?", Integer.class, result.pendingId())).isZero();
        assertThat(jdbc.queryForObject("SELECT purpose FROM public.account_email_outbox WHERE subject_id = ?", String.class, result.pendingId())).isEqualTo("SIGNUP_NOTICE");
    }
    @Test void takenUsernameHasSameResponseForVerifiedAndUnknownEmail() {
        Instant now = Instant.now();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String email = "verified" + suffix + "@example.test";
        String username = "taken" + suffix;
        jdbc.update("INSERT INTO public.users (id, username, password, active, email, email_verified_at) VALUES (?, ?, 'hash', TRUE, ?, ?)", UUID.randomUUID().toString(), username, email, now.toEpochMilli());
        for (String address : new String[]{email, "unknown" + suffix + "@example.test"}) {
            var data = new PendingSignupPort.Signup("t" + UUID.randomUUID().toString().replace("-", ""), username, "Owner", "hash", "RESTAURANT", address, "en", "v", now, false);
            assertThatThrownBy(() -> pending.start(data, now)).isInstanceOf(IllegalArgumentException.class).hasMessage("Choose an available username.");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.account_email_outbox WHERE recipient_email = ?", Integer.class, email)).isZero();
    }

    @Test void simultaneousVerificationReturnsInProgressThenVerified() throws Exception {
        Instant now = Instant.now();
        var input = signup("pe", "concurrent" + UUID.randomUUID() + "@example.test", now);
        var start = pending.start(input, now);
        var claim = outbox.claimForSubject(start.pendingId(), now).orElseThrow();
        String token = "b".repeat(43);
        assertThat(outbox.issue(claim, hash(token), now)).isPresent();
        outbox.sent(claim, now);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var provisioner = org.mockito.Mockito.mock(es.brasatech.fastbite.application.tenant.TenantProvisionerPort.class);
        var actual = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(service);
        var delegate = org.springframework.test.util.ReflectionTestUtils.getField(actual, "provisioner");
        org.mockito.Mockito.doAnswer(inv -> { entered.countDown(); if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); ((es.brasatech.fastbite.application.tenant.TenantProvisionerPort) delegate).provisionTenant(inv.getArgument(0)); return null; }).when(provisioner).provisionTenant(input.tenantId());
        var lifecycle = (es.brasatech.fastbite.application.tenant.TenantLifecyclePort) org.springframework.test.util.ReflectionTestUtils.getField(actual, "lifecycle");
        var mail = org.mockito.Mockito.mock(es.brasatech.fastbite.application.mail.AccountMailOutboxService.class);
        var concurrent = new PendingSignupService(pending, provisioner, lifecycle, mail, java.util.Optional.empty(), java.time.Clock.systemUTC());
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> concurrent.verify(token));
            try {
                assertThat(entered.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> concurrent.verify(token)).isInstanceOf(es.brasatech.fastbite.domain.tenant.TenantProvisioningInProgressException.class);
            } finally { release.countDown(); }
            assertThat(first.get(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(concurrent.verify(token)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_locations WHERE tenant_id = ?", Integer.class, input.tenantId())).isEqualTo(1);
    }
}
