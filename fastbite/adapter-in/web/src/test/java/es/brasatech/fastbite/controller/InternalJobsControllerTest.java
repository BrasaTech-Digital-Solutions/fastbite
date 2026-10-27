package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.SandboxProvisioningService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.VerifactuService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.service.ContractLifecycleJob;
import es.brasatech.fastbite.service.FiscalIssuerBackfillJob;
import es.brasatech.fastbite.service.SandboxProvisioningJob;
import es.brasatech.fastbite.service.VerifactuSubmissionJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cloud Scheduler running background jobs via POST /internal/jobs/run with X-FastBite-Jobs-Secret. */
class InternalJobsControllerTest {

    static final String SECRET = "a-jobs-secret-of-at-least-thirty-two-characters";

    @WebMvcTest(controllers = InternalJobsController.class)
    @ContextConfiguration(classes = {TestConfig.class, InternalJobsController.class, PlatformEnvironment.class,
            SecurityConfig.class, TenantRoutingResolver.class, WebConfig.class})
    abstract static class Endpoint {
        @Autowired MockMvc mockMvc;
        @MockitoBean SandboxProvisioningJob sandboxProvisioning;
        @MockitoBean VerifactuSubmissionJob verifactuSubmission;
        @MockitoBean FiscalIssuerBackfillJob fiscalIssuerBackfill;
        @MockitoBean ContractLifecycleJob contractLifecycle;
        @MockitoBean TenantLocationService tenantLocationService;

        @BeforeEach
        void setUp() {
            when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        }
    }

    @Nested
    @TestPropertySource(properties = {"fastbite.jobs.secret=" + SECRET})
    class Configured extends Endpoint {

        @Test
        void runsJobsAndReturnsSummaryWhenSecretMatches() throws Exception {
            when(sandboxProvisioning.run()).thenReturn(2);
            when(verifactuSubmission.run(any())).thenReturn(new VerifactuSubmissionJob.Summary(5, 3, 1));
            when(fiscalIssuerBackfill.run(any())).thenReturn(4);
            when(contractLifecycle.run(any())).thenReturn(2);

            mockMvc.perform(post("/internal/jobs/run")
                            .header(InternalJobsController.SECRET_HEADER, SECRET))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sandboxProvisioned").value(2))
                    .andExpect(jsonPath("$.verifactuSubmitted").value(5))
                    .andExpect(jsonPath("$.verifactuLocationsChecked").value(3))
                    .andExpect(jsonPath("$.verifactuLocationsRemaining").value(1))
                    .andExpect(jsonPath("$.fiscalIssuersBackfilled").value(4))
                    .andExpect(jsonPath("$.contractLifecycleActions").value(2));
        }

        @Test
        void refusesMissingOrWrongSecret() throws Exception {
            mockMvc.perform(post("/internal/jobs/run"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(post("/internal/jobs/run")
                            .header(InternalJobsController.SECRET_HEADER, "another-secret-of-at-least-thirty-two-chars"))
                    .andExpect(status().isUnauthorized());

            verify(sandboxProvisioning, never()).run();
            verify(verifactuSubmission, never()).run(any());
            verify(fiscalIssuerBackfill, never()).run(any());
        }

        @Test
        @WithMockUser(username = "owner", roles = {"OWNER", "ADMIN"})
        void isNotReachableBySessionOrRoleWithoutSecret() throws Exception {
            mockMvc.perform(post("/internal/jobs/run"))
                    .andExpect(status().isUnauthorized());

            verify(sandboxProvisioning, never()).run();
            verify(verifactuSubmission, never()).run(any());
            verify(fiscalIssuerBackfill, never()).run(any());
        }
    }

    @Nested
    @TestPropertySource(properties = {"fastbite.jobs.secret="})
    class Unconfigured extends Endpoint {

        @Test
        void returnsNotFoundWhenNoSecretIsConfigured() throws Exception {
            mockMvc.perform(post("/internal/jobs/run"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(post("/internal/jobs/run")
                            .header(InternalJobsController.SECRET_HEADER, SECRET))
                    .andExpect(status().isNotFound());

            verify(sandboxProvisioning, never()).run();
            verify(verifactuSubmission, never()).run(any());
            verify(fiscalIssuerBackfill, never()).run(any());
        }
    }

    @Test
    void refusesToStartWhenSecretIsShorterThanThirtyTwoCharacters() {
        var sandboxJob = mock(SandboxProvisioningJob.class);
        var verifactuJob = mock(VerifactuSubmissionJob.class);

        var backfillJob = mock(FiscalIssuerBackfillJob.class);

        assertThatThrownBy(() -> new InternalJobsController(sandboxJob, verifactuJob, backfillJob, "too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }

    @Test
    void inProcessFlagDisablesScheduledTimersWhileAllowingDirectRun() {
        var provisioning = mock(SandboxProvisioningService.class);
        when(provisioning.active()).thenReturn(true);
        when(provisioning.sendDue()).thenReturn(1);

        var sandboxJob = new SandboxProvisioningJob(provisioning, false);
        sandboxJob.sendDue();
        verify(provisioning, never()).sendDue();

        assertThat(sandboxJob.run()).isEqualTo(1);
        verify(provisioning).sendDue();

        var verifactu = mock(VerifactuService.class);
        var locations = mock(TenantLocationService.class);
        var verifactuJob = new VerifactuSubmissionJob(verifactu, locations, new PlatformEnvironment("sandbox"), false);
        verifactuJob.submitPending();
        verify(verifactu, never()).configured();
    }

    @Test
    void stopsStartingNewLocationsWhenFourMinuteTimeBudgetExpires() {
        var now = new AtomicReference<>(Instant.parse("2026-10-17T12:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };

        var verifactu = mock(VerifactuService.class);
        when(verifactu.configured()).thenReturn(true);
        when(verifactu.submitPending(anyBoolean(), anyBoolean())).thenAnswer(invocation -> {
            // Each location takes 2 minutes; after 2 locations (4 minutes), the budget is exhausted
            now.set(now.get().plus(Duration.ofMinutes(2)));
            return 3;
        });

        var locations = mock(TenantLocationService.class);
        when(locations.getAllLocations()).thenReturn(List.of(
                new TenantLocation("1", "ana", "loc1", "RESTAURANT", null),
                new TenantLocation("2", "ana", "loc2", "RESTAURANT", null),
                new TenantLocation("3", "luis", "loc3", "RESTAURANT", null)));

        var job = new VerifactuSubmissionJob(
                verifactu, locations, new PlatformEnvironment("production"), false, clock);
        Instant deadline = clock.instant().plus(VerifactuSubmissionJob.TIME_BUDGET);

        VerifactuSubmissionJob.Summary summary = job.run(deadline);

        assertThat(summary.submitted()).isEqualTo(6);
        assertThat(summary.locationsChecked()).isEqualTo(2);
        assertThat(summary.locationsRemaining()).isEqualTo(1);
    }
}
