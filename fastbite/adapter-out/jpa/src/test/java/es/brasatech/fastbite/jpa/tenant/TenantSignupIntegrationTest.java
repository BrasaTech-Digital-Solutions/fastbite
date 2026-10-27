package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.SignupConsents;
import es.brasatech.fastbite.application.tenant.TenantSignupService;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.TestConfig;
import es.brasatech.fastbite.jpa.user.UserEntity;
import es.brasatech.fastbite.jpa.user.UserJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = TestConfig.class)
class TenantSignupIntegrationTest {

    @Autowired
    private TenantSignupService tenantSignupService;

    @Autowired
    private UserJpaRepository userRepository;

    @Autowired
    private TenantLocationJpaRepository locationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private es.brasatech.fastbite.application.tenant.LegalAcceptancePort legalAcceptances;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void signupKeepsTheProofOfWhatTheOwnerAccepted() {
        Instant at = Instant.parse("2026-10-27T10:15:30Z");
        tenantSignupService.registerTenant("consentcafe", "consentowner", "hash", "Owner", "RESTAURANT",
                new SignupConsents("27/10/2026", false, at));
        tenantSignupService.registerTenant("optincafe", "optinowner", "hash", "Owner", "FREE",
                new SignupConsents("27/10/2026", true, at));

        assertEquals(List.of("terms"), documents("consentowner"));
        assertEquals(List.of("marketing", "terms"), documents("optinowner"));
        assertEquals("27/10/2026", jdbc.queryForObject(
                "SELECT version FROM public.legal_acceptances WHERE username = 'consentowner'", String.class));
        assertFalse(legalAcceptances.mayReceiveOffers("consentowner"));
        assertTrue(legalAcceptances.mayReceiveOffers("optinowner"));
    }

    @Test
    void marketingConsentCanBeWithdrawnAndGivenAgainWhileKeepingFullHistory() {
        Instant t1 = Instant.parse("2026-10-27T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-28T11:00:00Z");
        Instant t2Repeat = Instant.parse("2026-10-28T12:00:00Z");
        Instant t3 = Instant.parse("2026-10-29T09:30:00Z");
        Instant t4 = Instant.parse("2026-10-30T16:45:00Z");

        tenantSignupService.registerTenant("historycafe", "historyowner", "hash", "Owner", "RESTAURANT",
                new SignupConsents("27/10/2026", true, t1));
        assertTrue(legalAcceptances.mayReceiveOffers("historyowner"));

        // First withdrawal records withdrawn_at without deleting the row
        legalAcceptances.withdraw("historyowner", es.brasatech.fastbite.application.tenant.LegalAcceptancePort.MARKETING, t2);
        assertFalse(legalAcceptances.mayReceiveOffers("historyowner"));

        // Repeating withdrawal does not overwrite the original withdrawn_at timestamp
        legalAcceptances.withdraw("historyowner", es.brasatech.fastbite.application.tenant.LegalAcceptancePort.MARKETING, t2Repeat);
        assertEquals(t2, jdbc.queryForObject(
                "SELECT withdrawn_at FROM public.legal_acceptances WHERE username = 'historyowner' AND document = 'marketing'",
                java.sql.Timestamp.class).toInstant());

        // Re-consenting under the same version inserts a second row while preserving the first withdrawn row
        legalAcceptances.record("historyowner", es.brasatech.fastbite.application.tenant.LegalAcceptancePort.MARKETING, "27/10/2026", t3);
        assertTrue(legalAcceptances.mayReceiveOffers("historyowner"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.legal_acceptances WHERE username = 'historyowner' AND document = 'marketing'",
                Integer.class));

        // Withdrawing again stamps the second row while keeping the first row untouched
        legalAcceptances.withdraw("historyowner", es.brasatech.fastbite.application.tenant.LegalAcceptancePort.MARKETING, t4);
        assertFalse(legalAcceptances.mayReceiveOffers("historyowner"));

        var history = jdbc.query(
                "SELECT accepted_at, withdrawn_at FROM public.legal_acceptances WHERE username = 'historyowner' AND document = 'marketing' ORDER BY accepted_at",
                (rs, rowNum) -> List.of(rs.getTimestamp("accepted_at").toInstant(), rs.getTimestamp("withdrawn_at").toInstant()));
        assertEquals(List.of(List.of(t1, t2), List.of(t3, t4)), history);
    }

    private List<String> documents(String username) {
        return jdbc.queryForList("SELECT document FROM public.legal_acceptances WHERE username = ? ORDER BY document",
                String.class, username);
    }

    @Test
    void testTenantSignupAndSelfProvisioning() {
        String tenantId = "tenantnew";
        String adminUsername = "newtenantadmin";

        // Perform registration & self-provisioning
        assertDoesNotThrow(() ->
            tenantSignupService.registerTenant(tenantId, adminUsername, "bcrypt_password_hash", "New Tenant Owner")
        );

        // Verify the location registry contains this subdomain mapped to owner
        TenantContext.setCurrentTenant("default");
        transactionTemplate.execute(status -> {
            Optional<TenantLocationEntity> locOpt = locationRepository.findByTenantId(tenantId);
            assertTrue(locOpt.isPresent(), "Tenant location mapping should exist in master registry");
            assertEquals(adminUsername, locOpt.get().getOwnerUsername());
            return null;
        });

        // Switch back to default context and verify the owner user exists there
        TenantContext.setCurrentTenant("default");
        transactionTemplate.execute(status -> {
            Optional<UserEntity> userOpt = userRepository.findByUsername(adminUsername);
            assertTrue(userOpt.isPresent(), "Owner user should exist in the default schema");
            UserEntity user = userOpt.get();
            assertEquals("New Tenant Owner", user.getFullName());
            assertEquals("bcrypt_password_hash", user.getPassword());
            assertTrue(user.isActive());
            return null;
        });
    }
}
