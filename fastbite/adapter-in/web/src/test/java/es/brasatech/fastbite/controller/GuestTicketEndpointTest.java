package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.fiscal.FiscalTicketService;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.VerifactuService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.fiscal.*;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.verifactu.Verifactu;
import es.brasatech.fastbite.domain.verifactu.VerifactuRecord;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.service.GuestTicketSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {GuestTicketController.class, TicketController.class}, properties = "spring.messages.basename=i18n/messages")
@ContextConfiguration(classes = {TestConfig.class, GuestTicketController.class, TicketController.class,
        GuestTicketSignature.class, SecurityConfig.class, TenantRoutingResolver.class, WebConfig.class})
class GuestTicketEndpointTest {
    @Autowired MockMvc mvc;
    @Autowired GuestTicketSignature signatures;
    @MockitoBean FiscalTicketService tickets;
    @MockitoBean OrderService orders;
    @MockitoBean VerifactuService verifactu;
    @MockitoBean TenantLocationService locations;

    @BeforeEach void setup() {
        es.brasatech.fastbite.config.RegisteredTenants.all(locations);
        when(locations.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        when(locations.getLocationByCustomDomain("restaurant.example"))
                .thenReturn(Optional.of(new es.brasatech.fastbite.domain.tenant.TenantLocation(null, "owner", "kebab", "FREE", "restaurant.example")));
        FiscalTicket.Issuer issuer = new FiscalTicket.Issuer("Cafe", null, "12345678Z", "Street", "28001", "Madrid", null, null);
        FiscalTicket ticket = new FiscalTicket("abc", TicketKind.SIMPLIFIED, "TEST-T", 1, "TEST-T-000001", true,
                OffsetDateTime.parse("2026-10-31T10:00:00Z"), "online", issuer, null, List.of(), BigDecimal.ZERO,
                BigDecimal.TEN, VatBreakdown.EMPTY, BigDecimal.ZERO, PaymentMethod.ONLINE, null, null, null,
                List.of("order"), "1", null, "reference");
        when(tickets.findById("abc")).thenReturn(Optional.of(ticket));
        when(tickets.corrections("abc")).thenReturn(List.of());
        when(tickets.cancellation("abc")).thenReturn(Optional.empty());
        when(verifactu.findForTicket("abc")).thenReturn(Optional.empty());
    }

    @Test void prefixedGuestPageRendersAndRejectsBadSignatures() throws Exception {
        String sig = signatures.sign("kebab", "abc");
        mvc.perform(get("/kebab/ticket/abc").param("s", sig))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content().string(allOf(containsString("<title>TEST-T-000001</title>"), containsString("Cafe"),
                        containsString("TRAINING"), not(containsString("Served by")))));
        mvc.perform(get("/kebab/ticket/abc").param("s", sig + "0")).andExpect(status().isNotFound());
        mvc.perform(get("/kebab/ticket/abc")).andExpect(status().isNotFound());
    }

    @Test void tenantHostUsesTheSameSignedPage() throws Exception {
        mvc.perform(get("/ticket/abc").with(request -> { request.setServerName("restaurant.example"); return request; })
                .param("s", signatures.sign("kebab", "abc")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("TEST-T-000001")));
    }

    @Test void statusEndpointOnlyReturnsTheCurrentSessionsTicket() throws Exception {
        mvc.perform(get("/kebab/api/guest-ticket")).andExpect(status().isNotFound());
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("orderId", "order");
        session.setAttribute("orderTenant", "kebab");
        when(orders.findById("order")).thenReturn(Optional.of(mock(Order.class)));
        mvc.perform(get("/kebab/api/guest-ticket").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("pending"))
                .andExpect(jsonPath("$.ticketUrl").doesNotExist());
        FiscalTicket sale = tickets.findById("abc").orElseThrow();
        when(tickets.findForOrder("order")).thenReturn(Optional.of(sale));
        mvc.perform(get("/kebab/api/guest-ticket").session(session)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.state").value("ready"))
                .andExpect(jsonPath("$.number").value("TEST-T-000001"))
                .andExpect(jsonPath("$.ticketUrl").value("/kebab/ticket/abc?s=" + signatures.sign("kebab", "abc")));
        mvc.perform(get("/other/api/guest-ticket").session(session)).andExpect(status().isNotFound());
    }

    @Test void guestTicketShowsTaxQrAndRejectsSignaturesFromAnotherTenantOrTicket() throws Exception {
        FiscalTicket ticket = tickets.findById("abc").orElseThrow();
        VerifactuRecord record = new VerifactuRecord("rec", "abc", "TEST", 1, "F2", "12345678Z", "Cafe",
                ticket.fullNumber(), ticket.issuedOn(), ticket.issuedAt(), BigDecimal.ZERO, BigDecimal.TEN, null, "ABC",
                Verifactu.qrUrl(true, "12345678Z", ticket.fullNumber(), ticket.issuedOn(), BigDecimal.TEN),
                null, null, true, VerifactuRecord.Submission.PENDING);
        when(verifactu.findForTicket("abc")).thenReturn(Optional.of(record));
        mvc.perform(get("/kebab/ticket/abc").param("s", signatures.sign("kebab", "abc")))
                .andExpect(status().isOk()).andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(content().string(allOf(containsString("<svg"), containsString("VERI*FACTU"), containsString("TRAINING"))));
        mvc.perform(get("/kebab/ticket/abc").param("s", signatures.sign("other", "abc"))).andExpect(status().isNotFound());
        mvc.perform(get("/kebab/ticket/abc").param("s", signatures.sign("kebab", "different"))).andExpect(status().isNotFound());
        mvc.perform(get("/kebab/ticket/missing").param("s", signatures.sign("kebab", "missing"))).andExpect(status().isNotFound());
    }

    @Test void replacedAndRefundedTicketsShowOnlyTheAppropriateSignedLinks() throws Exception {
        FiscalTicket replacement = mock(FiscalTicket.class);
        when(replacement.kind()).thenReturn(TicketKind.REPLACEMENT);
        when(replacement.id()).thenReturn("replacement");
        when(replacement.fullNumber()).thenReturn("FULL-001");
        when(tickets.corrections("abc")).thenReturn(List.of(replacement));
        mvc.perform(get("/kebab/ticket/abc").param("s", signatures.sign("kebab", "abc")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("FULL-001")))
                .andExpect(content().string(not(containsString("/kebab/ticket/replacement?s="))));
        FiscalTicket refund = mock(FiscalTicket.class);
        when(refund.kind()).thenReturn(TicketKind.RECTIFYING);
        when(refund.fullNumber()).thenReturn("REFUND-001");
        when(refund.id()).thenReturn("refund");
        when(tickets.corrections("abc")).thenReturn(List.of(refund));
        mvc.perform(get("/kebab/ticket/abc").param("s", signatures.sign("kebab", "abc")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("REFUND-001")))
                .andExpect(content().string(containsString("/kebab/ticket/refund?s=" + signatures.sign("kebab", "refund"))));
    }

    @Test void cancelledGuestTicketDoesNotRevealStaffOrReason() throws Exception {
        when(tickets.cancellation("abc")).thenReturn(Optional.of(new TicketCancellation("abc", OffsetDateTime.now(), "staff-secret", "private-reason")));
        mvc.perform(get("/kebab/ticket/abc").param("s", signatures.sign("kebab", "abc")))
                .andExpect(status().isOk()).andExpect(content().string(allOf(containsString("cancelled"),
                        not(containsString("staff-secret")), not(containsString("private-reason")))));
    }
}
