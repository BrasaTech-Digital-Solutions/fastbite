package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.*;
import es.brasatech.fastbite.application.mail.OwnerSetupMailPort;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = es.brasatech.fastbite.jpa.TestConfig.class)
class TenantLifecycleIntegrationTest {
    @Autowired TenantSignupService signup;
    @Autowired TenantProvisionerPort provisioner;
    @Autowired TenantRegistrationPort registration;
    @Autowired TenantLifecyclePort lifecycle;
    @Autowired OwnerSetupService setup;
    @Autowired OwnerSetupMailPort mail;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @AfterEach void clear() { TenantContext.clear(); reset(mail); }

    @Test void duplicateSignupPreservesCredentialsAndCallerTenant() {
        TenantContext.setCurrentTenant("unrelated");
        signup.registerTenant("LifeCase", "lifeowner", "original-hash", "Owner");
        signup.registerTenant("lifecase", "lifeowner", "replacement-hash", "Changed");
        assertThat(TenantContext.getCurrentTenant()).isEqualTo("unrelated");
        assertThat(state("lifecase")).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT password FROM public.users WHERE username = 'lifeowner'", String.class))
                .isEqualTo("original-hash");
        assertThat(jdbc.queryForObject("SELECT password FROM tenant_lifecase.users WHERE username = 'lifeowner'", String.class))
                .isEqualTo("original-hash");
        assertThatThrownBy(() -> signup.registerTenant("LIFECASE", "anotherowner", "hash", "Other"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void failedSchemaSetupCanRetryWithoutPublishingALocation() {
        var failing = new TenantSignupService(tenant -> {
            provisioner.provisionTenant(tenant);
            throw new IllegalStateException("Injected crash after schema creation");
        }, registration, lifecycle);
        assertThatThrownBy(() -> failing.registerTenant("lifefailure", "lifefailowner", "hash", "Owner"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(state("lifefailure")).isEqualTo("FAILED");
        assertThat(locationCount("lifefailure")).isZero();
        signup.registerTenant("lifefailure", "lifefailowner", "hash", "Owner");
        assertThat(state("lifefailure")).isEqualTo("ACTIVE");
        assertThat(locationCount("lifefailure")).isEqualTo(1);
    }

    @Test void failureWritingTenantOwnerRollsBackThePublicAccountAndCanRetry() {
        provisioner.provisionTenant("lifeatomic");
        jdbc.update("INSERT INTO tenant_lifeatomic.users (id, username, password, active) VALUES ('conflict', 'lifeatomicowner', 'existing', TRUE)");
        assertThatThrownBy(() -> signup.registerTenant("lifeatomic", "lifeatomicowner", "newhash", "Owner"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.users WHERE username = 'lifeatomicowner'", Integer.class)).isZero();
        assertThat(locationCount("lifeatomic")).isZero();
        assertThat(state("lifeatomic")).isEqualTo("FAILED");
        jdbc.update("DELETE FROM tenant_lifeatomic.users WHERE id = 'conflict'");
        signup.registerTenant("lifeatomic", "lifeatomicowner", "newhash", "Owner");
        assertThat(state("lifeatomic")).isEqualTo("ACTIVE");
    }

    @Test void concurrentRegistrationsOnSeparateAdaptersRunTheWorkOnce() throws Exception {
        var otherInstance = new TenantLifecycleJdbcAdapter(dataSource);
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Runnable work = () -> {
            executions.incrementAndGet();
            started.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out"); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
            provisioner.provisionTenant("liferace");
            registration.createOwner("liferace", "liferaceowner", "hash", "Owner");
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> lifecycle.register("liferace", "signup:liferace", "liferaceowner", TenantLifecyclePort.State.ACTIVE, work));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> otherInstance.register("liferace", "signup:liferace", "liferaceowner", TenantLifecyclePort.State.ACTIVE, work));
            try { assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(es.brasatech.fastbite.domain.tenant.TenantProvisioningInProgressException.class); }
            finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
            otherInstance.register("liferace", "signup:liferace", "liferaceowner", TenantLifecyclePort.State.ACTIVE, work);
        }
        assertThat(executions.get()).isEqualTo(1);
        assertThat(locationCount("liferace")).isEqualTo(1);
    }

    @Test void committedAccountsRecoverAfterAnInterruptedLifecycleUpdate() {
        provisioner.provisionTenant("lifeinterrupted");
        jdbc.update("INSERT INTO public.tenant_lifecycle VALUES ('lifeinterrupted', 'signup:lifeinterrupted', 'lifeinterruptowner', 'PROVISIONING', 0)");
        registration.createOwner("lifeinterrupted", "lifeinterruptowner", "original", "Owner");
        signup.registerTenant("lifeinterrupted", "lifeinterruptowner", "replacement", "Owner");
        assertThat(state("lifeinterrupted")).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT password FROM public.users WHERE username = 'lifeinterruptowner'", String.class)).isEqualTo("original");
    }

    @Test void additionalLocationCannotBeClaimedByAnotherOwnerOrCheckout() {
        signup.registerTenant("lifeprimary", "lifeprimaryowner", "hash", "Owner");
        signup.registerTenant("lifesecond", "lifesecondowner", "hash", "Owner");
        signup.registerAdditionalLocation("LifeExtra", "lifeprimaryowner", "RESTAURANT", "checkout:lifeextra");
        signup.registerAdditionalLocation("lifeextra", "lifeprimaryowner", "RESTAURANT", "checkout:lifeextra");
        assertThat(locationCount("lifeextra")).isEqualTo(1);
        assertThatThrownBy(() -> signup.registerAdditionalLocation("lifeextra", "lifesecondowner", "RESTAURANT", "checkout:lifeextra"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signup.registerAdditionalLocation("lifeextra", "lifeprimaryowner", "RESTAURANT", "checkout:different"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signup.registerAdditionalLocation("lifenoowner", "missing", "RESTAURANT"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(locationCount("lifenoowner")).isZero();
    }

    @Test void mailFailureRetriesAndSuccessfulDuplicatesKeepTheSameLinkUntilExpiry() {
        reset(mail);
        var invitation = new OwnerSetupPort.Invitation("lifemail", "lifemail", "lifemailowner", "Owner", "owner@example.test", "RESTAURANT");
        doThrow(new IllegalStateException("SMTP unavailable")).doNothing().when(mail).sendSetupLink(anyString(), anyString(), anyString());
        assertThatThrownBy(() -> setup.invite(invitation)).isInstanceOf(IllegalStateException.class);
        assertThat(state("lifemail")).isEqualTo("FAILED");
        setup.invite(invitation);
        String originalHash = jdbc.queryForObject("SELECT token_hash FROM public.owner_setup_tokens WHERE checkout_id = 'lifemail'", String.class);
        setup.invite(invitation);
        verify(mail, times(2)).sendSetupLink(anyString(), anyString(), anyString());
        assertThat(state("lifemail")).isEqualTo("AWAITING_OWNER");
        assertThat(jdbc.queryForObject("SELECT token_hash FROM public.owner_setup_tokens WHERE checkout_id = 'lifemail'", String.class)).isEqualTo(originalHash);
        jdbc.update("UPDATE public.owner_setup_tokens SET expires_at = ? WHERE checkout_id = 'lifemail'", Instant.now().minusSeconds(1).toEpochMilli());
        setup.invite(invitation);
        verify(mail, times(3)).sendSetupLink(anyString(), anyString(), anyString());
        var links = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mail, times(3)).sendSetupLink(anyString(), anyString(), links.capture());
        String token = links.getAllValues().getLast().split("token=")[1];
        assertThat(setup.complete(token, "secure-hash")).isTrue();
        assertThat(state("lifemail")).isEqualTo("ACTIVE");
        setup.invite(invitation);
        verify(mail, times(3)).sendSetupLink(anyString(), anyString(), anyString());
    }

    @Test void reservedAndMalformedNamesNeverReachDdl() {
        for (String tenant : new String[]{"kebab", "default", "OWNER", "bad-name", "x;drop", "a".repeat(57)}) {
            assertThatThrownBy(() -> signup.registerTenant(tenant, "unused", "hash", "Owner"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private String state(String tenant) {
        return jdbc.queryForObject("SELECT state FROM public.tenant_lifecycle WHERE tenant_id = ?", String.class, tenant);
    }

    private int locationCount(String tenant) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_locations WHERE tenant_id = ?", Integer.class, tenant);
    }
}
