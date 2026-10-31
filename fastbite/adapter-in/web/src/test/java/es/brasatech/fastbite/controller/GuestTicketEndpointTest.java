package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.fiscal.FiscalTicketService;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.VerifactuService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.fiscal.*;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.service.GuestTicketSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
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
}
