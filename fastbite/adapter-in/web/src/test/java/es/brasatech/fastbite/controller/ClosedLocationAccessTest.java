package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.application.office.UserService;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.platform.PlatformAdmins;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.settings.GuestPrivacyNoticeService;
import es.brasatech.fastbite.application.settings.RestaurantSettingsService;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.application.tenant.*;
import es.brasatech.fastbite.application.verifactu.VerifactuService;
import es.brasatech.fastbite.config.ThemeCatalog;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.fiscal.*;
import es.brasatech.fastbite.domain.tenant.BillingAccount;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.domain.verifactu.VerifactuRecord;
import es.brasatech.fastbite.domain.verifactu.VerifactuStatus;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.security.TenantUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies that once a location is closed (after 30 days paused + 30 days export window),
 * its public restaurant pages (/closedbistro/menu) return 404, while its owner can still
 * consult and download its VERI*FACTU billing records and issued fiscal tickets from the
 * owner console.
 */
@WebMvcTest(controllers = {OwnerWorkspaceController.class})
@ContextConfiguration(classes = {TestConfig.class, OwnerWorkspaceController.class,
        PlatformEnvironment.class, PlatformLink.class, PlatformAdmins.class, ThemeCatalog.class,
        SecurityConfig.class, TenantRoutingResolver.class, WebConfig.class})
class ClosedLocationAccessTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRoutingResolver routingResolver;

    @MockitoBean TenantLocationService tenantLocationService;
    @MockitoBean OwnerWorkspaceService workspace;
    @MockitoBean OwnerStaffService staff;
    @MockitoBean SubscriptionService subscriptions;
    @MockitoBean TenantBackupRestorePort backups;
    @MockitoBean AppearancePort appearance;
    @MockitoBean FreePlanLock freePlanLock;
    @MockitoBean LegalAcceptancePort acceptances;
    @MockitoBean GuestPrivacyNoticeService privacyNotices;
    @MockitoBean UserService userService;

    private static final Instant CLOSED_AT = Instant.parse("2026-12-15T10:00:00Z");

    @BeforeEach
    void setUp() {
        routingResolver.evictTenant("closedbistro");
        TenantLocation closedLoc = new TenantLocation(
                "1", "ana", "closedbistro", "RESTAURANT", null, null, false, false, CLOSED_AT);
        when(tenantLocationService.getLocation("closedbistro")).thenReturn(Optional.of(closedLoc));
        when(tenantLocationService.isClosed("closedbistro")).thenReturn(true);
        when(tenantLocationService.getLocationsByOwner("ana")).thenReturn(List.of(closedLoc));
        when(tenantLocationService.isOwnerOf("ana", "closedbistro")).thenReturn(true);
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());

        BillingAccount closedBilling = new BillingAccount(
                "closedbistro", "ana", "key", Instant.parse("2026-09-15T10:00:00Z"),
                "LOCAL_TRIAL", null, null, 0, 0, false, null, null, 0, 0, null, false,
                Instant.parse("2026-10-15T10:00:00Z"),
                Instant.parse("2026-10-15T10:05:00Z"),
                Instant.parse("2026-11-07T10:00:00Z"),
                Instant.parse("2026-11-15T10:00:00Z"),
                CLOSED_AT);
        when(subscriptions.account("closedbistro", "ana")).thenReturn(closedBilling);
        when(subscriptions.isClosed("closedbistro")).thenReturn(true);
    }

    @Test
    void closedLocationMenuReturns404WhileOwnerCanViewAndDownloadFiscalRecordsAndTickets() throws Exception {
        // 1. Public guest menu for the closed location returns 404
        mockMvc.perform(get("/closedbistro/menu"))
                .andExpect(status().isNotFound());

        // 2. Owner can still download VERI*FACTU billing records as JSON
        VerifactuRecord record = new VerifactuRecord(
                "rec-1", "t-1", VerifactuRecord.REAL_CHAIN, 1, "F2", "B12345678",
                "Bistro SL", "BIS-T26-000001", java.time.LocalDate.of(2026, 10, 1),
                OffsetDateTime.of(2026, 10, 1, 13, 0, 0, 0, ZoneOffset.UTC),
                new BigDecimal("1.00"), new BigDecimal("11.00"), null,
                "ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890",
                "https://aeat.example/qr", null, null, false,
                new VerifactuRecord.Submission(VerifactuStatus.ACCEPTED, 1, null, "CSV123",
                        OffsetDateTime.of(2026, 10, 1, 13, 1, 0, 0, ZoneOffset.UTC)));
        when(workspace.verifactuRecords("ana", "closedbistro", true)).thenReturn(List.of(record));
        when(workspace.verifactuSummary("ana", "closedbistro", true))
                .thenReturn(new VerifactuService.Summary(true, "AEAT", true,
                        java.util.Map.of(VerifactuStatus.ACCEPTED, 1L), record, List.of(), true));

        FiscalTicket ticket = new FiscalTicket(
                "t-1", TicketKind.SIMPLIFIED, "BIS-T26", 1, "BIS-T26-000001", false,
                OffsetDateTime.of(2026, 10, 1, 13, 0, 0, 0, ZoneOffset.UTC), "ana",
                new FiscalTicket.Issuer("Bistro SL", "Bistro", "B12345678", "Calle 1", "28001", "Madrid", "Madrid", null),
                null,
                List.of(new TicketLine("Burger", 1, new BigDecimal("11.00"), new BigDecimal("11.00"), new BigDecimal("10"), List.of())),
                BigDecimal.ZERO, new BigDecimal("11.00"),
                new VatBreakdown(List.of(new VatBreakdown.Line(new BigDecimal("10"), new BigDecimal("10.00"), new BigDecimal("1.00"), new BigDecimal("11.00"))),
                        new BigDecimal("11.00"), new BigDecimal("10.00"), new BigDecimal("1.00")),
                BigDecimal.ZERO, PaymentMethod.CARD, null, BigDecimal.ZERO, null, List.of("ord-1"), "1", null, "pi_1");
        when(workspace.fiscalTickets("ana", "closedbistro")).thenReturn(List.of(ticket));
        when(workspace.fiscalTicket("ana", "closedbistro", "t-1")).thenReturn(Optional.of(ticket));
        when(workspace.verifactuRecordForTicket("ana", "closedbistro", "t-1")).thenReturn(Optional.of(record));
        when(workspace.fiscalProfile("ana", "closedbistro")).thenReturn(FiscalProfile.EMPTY);

        TenantUser ownerPrincipal = new TenantUser("ana", "hash", true,
                List.of(new SimpleGrantedAuthority("ROLE_OWNER")), null);

        mockMvc.perform(get("/owner/workspace/closedbistro/verifactu/records.json").with(user(ownerPrincipal)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"verifactu-closedbistro.json\""))
                .andExpect(jsonPath("$[0].invoiceNumber").value("BIS-T26-000001"));

        // 3. Owner can download issued fiscal tickets as JSON and open the printable ticket view
        mockMvc.perform(get("/owner/workspace/closedbistro/tickets.json").with(user(ownerPrincipal)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"tickets-closedbistro.json\""))
                .andExpect(jsonPath("$[0].fullNumber").value("BIS-T26-000001"));

        mockMvc.perform(get("/owner/workspace/closedbistro/tickets/t-1").with(user(ownerPrincipal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("BIS-T26-000001")));

        // 4. Owner console shows "Closed on 15/12/2026" and the read-only fiscal records & tickets view
        mockMvc.perform(get("/owner/console").param("location", "closedbistro").with(user(ownerPrincipal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Closed on 15/12/2026")))
                .andExpect(content().string(containsString("/owner/workspace/closedbistro/verifactu/records.json")))
                .andExpect(content().string(containsString("/owner/workspace/closedbistro/tickets.json")))
                .andExpect(content().string(containsString("BIS-T26-000001")));

        // 5. Mutating endpoints on the closed location are refused with 409 Conflict
        mockMvc.perform(post("/owner/workspace/closedbistro/service")
                        .with(user(ownerPrincipal))
                        .with(csrf())
                        .param("dineIn", "true")
                        .param("takeaway", "true"))
                .andExpect(status().isConflict());
    }
}
