package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.OwnerSetupPort;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.TestConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = TestConfig.class)
class OwnerSetupIntegrationTest {
    @Autowired OwnerSetupJdbcAdapter setup;
    @Autowired TenantProvisionerAdapter provisioner;
    @Autowired DataSource dataSource;

    @AfterEach void clearTenant() { TenantContext.clear(); }

    private OwnerSetupPort.Invitation invitation() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String tenant = "setup" + suffix;
        provisioner.provisionTenant(tenant);
        return new OwnerSetupPort.Invitation("checkout-" + suffix, tenant, "owner" + suffix, "Owner", "owner@example.test", "RESTAURANT");
    }

    private Object value(String sql) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getObject(1);
        }
    }

    @Test void disabledOwnersAreEnabledTogetherAndLinkCannotBeReused() throws Exception {
        var invitation = invitation();
        String hash = UUID.randomUUID().toString();
        TenantContext.setCurrentTenant("unrelated");
        assertTrue(setup.prepare(invitation, hash, Instant.now().plusSeconds(60)));
        for (String schema : new String[]{"public", "tenant_" + invitation.tenantId()}) {
            assertEquals(false, value("SELECT active FROM " + schema + ".users WHERE username = '" + invitation.username() + "'"));
        }
        assertTrue(setup.isValid(hash, Instant.now()));
        assertTrue(setup.complete(hash, "encoded-new-password", Instant.now()));
        assertFalse(setup.complete(hash, "attacker", Instant.now()));
        assertFalse(setup.prepare(invitation, "another-token", Instant.now().plusSeconds(60)));
        for (String schema : new String[]{"public", "tenant_" + invitation.tenantId()}) {
            assertEquals(true, value("SELECT active FROM " + schema + ".users WHERE username = '" + invitation.username() + "'"));
            assertEquals("encoded-new-password", value("SELECT password FROM " + schema + ".users WHERE username = '" + invitation.username() + "'"));
        }
        assertEquals("unrelated", TenantContext.getCurrentTenant());
    }

    @Test void retriesReplaceExpiredTokensButNeverOverwriteAnExistingAccount() {
        var invitation = invitation();
        String old = UUID.randomUUID().toString(), fresh = UUID.randomUUID().toString();
        setup.prepare(invitation, old, Instant.now().minusSeconds(1));
        assertFalse(setup.isValid(old, Instant.now()));
        assertFalse(setup.complete(old, "hash", Instant.now()));
        assertTrue(setup.prepare(invitation, fresh, Instant.now().plusSeconds(60)));
        assertFalse(setup.isValid(old, Instant.now()));
        assertTrue(setup.isValid(fresh, Instant.now()));
        var other = invitation();
        var collision = new OwnerSetupPort.Invitation(other.checkoutId(), other.tenantId(), invitation.username(), "Other", "other@example.test", "RESTAURANT");
        assertThrows(IllegalStateException.class, () -> setup.prepare(collision, "collision-token", Instant.now().plusSeconds(60)));
        var changedEmail = new OwnerSetupPort.Invitation(invitation.checkoutId(), invitation.tenantId(), invitation.username(), "Owner", "attacker@example.test", "RESTAURANT");
        assertThrows(IllegalStateException.class, () -> setup.prepare(changedEmail, "bad-token", Instant.now().plusSeconds(60)));
    }

    @Test void concurrentSubmissionsHaveExactlyOneWinner() throws Exception {
        var invitation = invitation();
        String hash = UUID.randomUUID().toString();
        setup.prepare(invitation, hash, Instant.now().plusSeconds(60));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return setup.complete(hash, "first", Instant.now()); });
            var second = executor.submit(() -> { start.await(); return setup.complete(hash, "second", Instant.now()); });
            start.countDown();
            assertNotEquals(first.get(), second.get());
        }
    }

    @Test void failureUpdatingTenantAccountRollsBackPlatformPasswordAndToken() throws Exception {
        var invitation = invitation();
        String hash = UUID.randomUUID().toString();
        setup.prepare(invitation, hash, Instant.now().plusSeconds(60));
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM tenant_" + invitation.tenantId() + ".users");
        }
        assertThrows(IllegalStateException.class, () -> setup.complete(hash, "new", Instant.now()));
        assertTrue(setup.isValid(hash, Instant.now()));
        assertEquals(false, value("SELECT active FROM public.users WHERE username = '" + invitation.username() + "'"));
    }
}
