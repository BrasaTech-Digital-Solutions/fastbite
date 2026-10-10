package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.fiscal.FiscalTicketService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.PaymentMethod;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.fiscal.TicketLine;
import es.brasatech.fastbite.domain.fiscal.VatBreakdown;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.security.TenantUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TicketController.class, properties = "spring.messages.basename=i18n/messages")
@ContextConfiguration(classes = {TestConfig.class, TicketController.class, BadRequestAdvice.class, SecurityConfig.class,
        TenantRoutingResolver.class, WebConfig.class})
class TicketEndpointTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private FiscalTicketService tickets;
    @MockitoBean private es.brasatech.fastbite.application.verifactu.VerifactuService verifactu;
    @MockitoBean private TenantLocationService tenantLocationService;

    private static final FiscalTicket SALE = new FiscalTicket("t1", TicketKind.SIMPLIFIED, "KH-T26", 7, "KH-T26-000007",
            false, OffsetDateTime.parse("2026-10-09T13:45:00+02:00"), "carla",
            new FiscalTicket.Issuer("Ana García López", "Kebab House", "12345678Z", "Calle Mayor 12", "28013", "Madrid",
                    "Madrid", null),
            null,
            List.of(new TicketLine("Kebab", 2, new BigDecimal("7.00"), new BigDecimal("14.00"), new BigDecimal("10.00"),
                    List.of(new TicketLine.Option("Garlic sauce", new BigDecimal("0.50"), 1)))),
            BigDecimal.ZERO, new BigDecimal("14.00"),
            VatBreakdown.of(List.of(new VatBreakdown.Amount(new BigDecimal("14.00"), new BigDecimal("10.00")))),
            BigDecimal.ZERO, PaymentMethod.CASH, new BigDecimal("20.00"), new BigDecimal("6.00"), null,
            List.of("o1"), "12", "Terrace 4", null);

    @BeforeEach
    void setUp() {
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        when(tickets.findById("t1")).thenReturn(Optional.of(SALE));
        when(tickets.today()).thenReturn(LocalDate.of(2026, 10, 9));
        when(tickets.issuedOn(any())).thenReturn(List.of(SALE));
    }

    private static MockHttpServletRequestBuilder as(String role, MockHttpServletRequestBuilder request) {
        var authorities = AuthorityUtils.createAuthorityList(role);
        var user = new TenantUser("carla", "hash", true, authorities, "kebab");
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT",
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities)));
        return request.session(session).with(csrf()).header("Referer", "http://localhost/kebab/counter")
                .contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void thePrintedTicketCarriesTheLegalDetails() throws Exception {
        mockMvc.perform(as("ROLE_CASHIER", get("/kebab/counter/tickets/t1")).locale(Locale.forLanguageTag("es")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Factura simplificada"),
                        containsString("KH-T26-000007"),
                        containsString("NIF 12345678Z"),
                        containsString("Ana García López"),
                        containsString("09/10/2026 13:45"),
                        containsString("Garlic sauce"),
                        containsString("12,73"),  // VAT base at 10%
                        containsString("1,27"),   // VAT
                        containsString("Cambio"),
                        containsString("6,00"))));
    }

    @Test
    void thePrintedTicketCarriesItsTaxQrCode() throws Exception {
        var record = new es.brasatech.fastbite.domain.verifactu.VerifactuRecord("rec1", "t1", "REAL", 1, "F2",
                "12345678Z", "Ana García López", "KH-T26-000007", SALE.issuedOn(), SALE.issuedAt(), new BigDecimal("1.27"),
                new BigDecimal("14.00"), null, "ABC", es.brasatech.fastbite.domain.verifactu.Verifactu.qrUrl(true,
                "12345678Z", "KH-T26-000007", SALE.issuedOn(), new BigDecimal("14.00")), null, false,
                es.brasatech.fastbite.domain.verifactu.VerifactuRecord.Submission.PENDING);
        when(verifactu.findForTicket("t1")).thenReturn(Optional.of(record));

        mockMvc.perform(as("ROLE_CASHIER", get("/kebab/counter/tickets/t1")).locale(Locale.forLanguageTag("es")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("QR tributario:"),
                        containsString("<svg"),
                        containsString("VERI*FACTU"),
                        containsString("Factura verificable en la sede electrónica de la AEAT"))));
    }

    @Test
    void theDayListShowsTicketsAndWhatCanBeDoneWithThem() throws Exception {
        mockMvc.perform(as("ROLE_MANAGER", get("/kebab/counter/tickets")).locale(Locale.ENGLISH))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("KH-T26-000007"), containsString("Full invoice"),
                        containsString("data-action=\"refund\""))));
    }

    @Test
    void cashiersCannotRefund() throws Exception {
        mockMvc.perform(as("ROLE_CASHIER", post("/counter/api/tickets/t1/refund")).content("{\"reason\":\"Wrong dish\"}")
                        .locale(Locale.forLanguageTag("es")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Solo un encargado puede hacer devoluciones."));
        verify(tickets, never()).refund(any(), any(), any());
    }

    @Test
    void managersRefundAndGetTheCorrectiveInvoice() throws Exception {
        FiscalTicket refund = new FiscalTicket("r1", TicketKind.RECTIFYING, "KH-R26", 1, "KH-R26-000001", false,
                SALE.issuedAt(), "maria", SALE.issuer(), null, SALE.lines(), BigDecimal.ZERO, new BigDecimal("-14.00"),
                SALE.vat(), BigDecimal.ZERO, PaymentMethod.CASH, null, null,
                new FiscalTicket.Correction("t1", "KH-T26-000007", SALE.issuedOn(), "Wrong dish"), List.of("o1"), "12", null, null);
        when(tickets.refund("t1", "Wrong dish", "carla")).thenReturn(refund);

        mockMvc.perform(as("ROLE_MANAGER", post("/counter/api/tickets/t1/refund")).content("{\"reason\":\"Wrong dish\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullNumber").value("KH-R26-000001"))
                .andExpect(jsonPath("$.kind").value("RECTIFYING"));
    }

    @Test
    void refusalsAreExplainedInTheCashiersLanguage() throws Exception {
        when(tickets.settle(any(), any(), any(), eq("carla"))).thenThrow(new LocalizedRejection("ticket.error.orderPaid",
                "Order #12 is already paid", 12));

        mockMvc.perform(as("ROLE_CASHIER", post("/counter/api/settle"))
                        .content("{\"orderIds\":[\"o1\"],\"paymentMethod\":\"CARD\"}").locale(Locale.forLanguageTag("es")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("El pedido n.º 12 ya está pagado."));
    }

    @Test
    void anUnknownPaymentMethodIsRefusedBeforeAnythingIsCharged() throws Exception {
        mockMvc.perform(as("ROLE_CASHIER", post("/counter/api/settle"))
                        .content("{\"orderIds\":[\"o1\"],\"paymentMethod\":\"GOLD\"}"))
                .andExpect(status().isBadRequest());
        verify(tickets, never()).settle(any(), any(), any(), any());
    }
}
