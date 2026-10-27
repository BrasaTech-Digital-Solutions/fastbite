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
