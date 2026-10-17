package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.*;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.tenant.TenantProvisioningInProgressException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = es.brasatech.fastbite.jpa.TestConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=3000"
})
class TenantLifecyclePoolDeadlockTest {

    @Autowired TenantSignupService signup;
    @Autowired TenantProvisionerPort provisioner;
    @Autowired TenantRegistrationPort registration;
    @Autowired TenantLifecyclePort lifecycle;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void concurrentRegistrationsOfDifferentTenantsBothSucceedUnderPoolTwo() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var f1 = executor.submit(() -> {
                signup.registerTenant("pooldiff1", "pooldiffowner1", "hash", "Owner 1");
                return null;
            });
            var f2 = executor.submit(() -> {
                signup.registerTenant("pooldiff2", "pooldiffowner2", "hash", "Owner 2");
                return null;
            });

            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);

            assertThat(state("pooldiff1")).isEqualTo("ACTIVE");
            assertThat(state("pooldiff2")).isEqualTo("ACTIVE");
            assertThat(locationCount("pooldiff1")).isEqualTo(1);
            assertThat(locationCount("pooldiff2")).isEqualTo(1);
        }
    }

    @Test
    void concurrentRegistrationsOfSameTenantOneSucceedsOneGetsInProgressWithoutTimeout() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();

        Runnable work = () -> {
            executions.incrementAndGet();
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for test latch");
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            provisioner.provisionTenant("poolsame");
            registration.createOwner("poolsame", "poolsameowner", "hash", "Owner");
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var f1 = executor.submit(() -> {
                lifecycle.register("poolsame", "signup:poolsame", "poolsameowner", TenantLifecyclePort.State.ACTIVE, work);
                return null;
            });

            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            var f2 = executor.submit(() -> {
                lifecycle.register("poolsame", "signup:poolsame", "poolsameowner", TenantLifecyclePort.State.ACTIVE, work);
                return null;
            });

            try {
                assertThatThrownBy(() -> f2.get(2, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(TenantProvisioningInProgressException.class);
            } finally {
                release.countDown();
            }

            f1.get(5, TimeUnit.SECONDS);

            assertThat(state("poolsame")).isEqualTo("ACTIVE");
            assertThat(executions.get()).isEqualTo(1);
        }
    }

    private String state(String tenant) {
        return jdbc.queryForObject("SELECT state FROM public.tenant_lifecycle WHERE tenant_id = ?",
                String.class, tenant.toLowerCase());
    }

    private int locationCount(String tenant) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_locations WHERE LOWER(tenant_id) = ?",
                Integer.class, tenant.toLowerCase());
    }
}
