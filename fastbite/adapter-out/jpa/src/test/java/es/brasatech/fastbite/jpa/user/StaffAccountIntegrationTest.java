package es.brasatech.fastbite.jpa.user;

import es.brasatech.fastbite.jpa.TestConfig;
import es.brasatech.fastbite.jpa.tenant.TenantProvisionerAdapter;
import es.brasatech.fastbite.jpa.tenant.AccountMailOutboxJdbcAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = TestConfig.class)
class StaffAccountIntegrationTest {
    @Autowired StaffAccountJdbcAdapter staff;
    @Autowired PasswordRecoveryJdbcAdapter recovery;
    @Autowired TenantProvisionerAdapter provisioner;
    @Autowired AccountMailOutboxJdbcAdapter outbox;
    @Autowired JdbcTemplate jdbc;
    @Test void ownerAndManagerHelpStaffButManagerCannotChangePeers() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String tenant = "st" + suffix, schema = "tenant_" + tenant;
        provisioner.provisionTenant(tenant);
        jdbc.update("INSERT INTO public.tenant_locations (id, owner_username, tenant_id, plan) VALUES (?, ?, ?, 'RESTAURANT')", UUID.randomUUID().toString(), "owner" + suffix, tenant);
        String manager = UUID.randomUUID().toString(), cashier = UUID.randomUUID().toString(), peer = UUID.randomUUID().toString();
        user(schema, manager, "manager" + suffix, "MANAGER"); user(schema, cashier, "cashier" + suffix, "CASHIER"); user(schema, peer, "peer" + suffix, "MANAGER");
        Instant now = Instant.now(); String email = "staff" + suffix + "@example.test";
        assertThatThrownBy(() -> staff.setPassword("manager" + suffix, false, tenant, peer, "bad")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> staff.setPassword("intruder", false, tenant, cashier, "bad")).isInstanceOf(SecurityException.class);
        assertThat(staff.setPassword("manager" + suffix, false, tenant, cashier, "new").username()).isEqualTo("cashier" + suffix);
        assertThat(staff.enqueueReset("manager" + suffix, false, tenant, cashier, now)).isEmpty();
        assertThat(recovery.enqueue("cashier" + suffix, tenant, now)).isEmpty();
        staff.setEmail("owner" + suffix, true, tenant, cashier, email, "en", now);
        var verify = outbox.claimForSubject(cashier, now).orElseThrow();
        assertThat(outbox.issue(verify, "staff-verify-" + suffix, now)).isPresent(); outbox.sent(verify, now);
        assertThat(staff.verifyEmail("staff-verify-" + suffix, now)).isTrue();
        assertThat(staff.verifyEmail("staff-verify-" + suffix, now)).isTrue();
        assertThat(staff.enqueueReset("manager" + suffix, false, tenant, cashier, now.plusSeconds(61))).isPresent();
        var reset = outbox.claimForSubject(cashier, now.plusSeconds(61)).orElseThrow();
        assertThat(outbox.issue(reset, "staff-reset-" + suffix, now.plusSeconds(61))).isPresent(); outbox.sent(reset, now.plusSeconds(61));
        assertThat(recovery.consume("staff-reset-" + suffix, "reset", now.plusSeconds(62))).hasValueSatisfying(account -> assertThat(account.tenantId()).isEqualTo(tenant));
        assertThat(jdbc.queryForObject("SELECT password FROM " + schema + ".users WHERE id = ?", String.class, cashier)).isEqualTo("reset");
        assertThat(recovery.enqueue("cashier" + suffix, tenant, now.plusSeconds(123))).isPresent();
    }
    @Test void missingAndForeignStaffAreIndistinguishableFromMalformedIds() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String tenant = "st" + suffix, other = "ot" + suffix, actor = "owner" + suffix;
        provisioner.provisionTenant(tenant);
        provisioner.provisionTenant(other);
        jdbc.update("INSERT INTO public.tenant_locations (id, owner_username, tenant_id, plan) VALUES (?, ?, ?, 'RESTAURANT')", UUID.randomUUID().toString(), actor, tenant);
        String foreign = UUID.randomUUID().toString();
        user("tenant_" + other, foreign, "foreign" + suffix, "CASHIER");
        for (String id : java.util.List.of(UUID.randomUUID().toString(), "not-a-uuid", foreign)) {
            assertThat(staff.find(actor, true, tenant, id)).isEmpty();
            assertThatThrownBy(() -> staff.setPassword(actor, true, tenant, id, "hash")).isInstanceOf(es.brasatech.fastbite.domain.exception.ResourceNotFoundException.class);
            assertThatThrownBy(() -> staff.setEmail(actor, true, tenant, id, "a@example.test", "en", Instant.now())).isInstanceOf(es.brasatech.fastbite.domain.exception.ResourceNotFoundException.class);
            assertThatThrownBy(() -> staff.enqueueReset(actor, true, tenant, id, Instant.now())).isInstanceOf(es.brasatech.fastbite.domain.exception.ResourceNotFoundException.class);
        }
    }
    private void user(String schema, String id, String name, String role) {
        jdbc.update("INSERT INTO " + schema + ".users (id, username, password, full_name, active) VALUES (?, ?, 'hash', ?, TRUE)", id, name, name);
        jdbc.update("INSERT INTO " + schema + ".user_roles (user_id, role) VALUES (?, ?)", id, role);
    }
}
