package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.fiscal.FiscalTicketService;
import es.brasatech.fastbite.application.fiscal.GuestActionRateLimit;
import es.brasatech.fastbite.application.fiscal.GuestInvoiceRateLimit;
import es.brasatech.fastbite.application.mail.GuestTicketMailPort;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationGate;
import es.brasatech.fastbite.config.PublicOrigin;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.service.GuestTicketSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuestTicketControllerTest {
    private final FiscalTicketService tickets = mock(FiscalTicketService.class);
    private final OrderService orders = mock(OrderService.class);
    private final TicketController counter = mock(TicketController.class);
    private final GuestTicketSignature signatures = new GuestTicketSignature("a-test-secret");
    private final GuestTicketController controller = new GuestTicketController(tickets, orders, signatures, counter, mock(GuestInvoiceRateLimit.class), mock(MessageSource.class), mock(GuestActionRateLimit.class), mock(GuestTicketMailPort.class), mock(PublicOrigin.class), mock(VerifactuAuthorizationGate.class));
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void signaturesAreScopedAndVersioned() {
        String sig = signatures.sign("kebab", "ticket-1");
        assertThat(sig).startsWith("v1.");
        assertThat(signatures.valid("KEBAB", "ticket-1", sig)).isTrue();
        assertThat(signatures.valid("other", "ticket-1", sig)).isFalse();
        assertThat(signatures.valid("kebab", "ticket-2", sig)).isFalse();
        assertThat(signatures.valid("kebab", "ticket-1", sig + "0")).isFalse();
        assertThat(signatures.valid("kebab", "ticket-1", null)).isFalse();
    }

    @Test void missingAndWrongSignaturesAre404BeforeTicketLookup() {
        TenantContext.setCurrentTenant("kebab");
        assertThatThrownBy(() -> controller.ticket("one", null, new MockHttpServletRequest(),
                new MockHttpServletResponse(), new ExtendedModelMap())).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        verifyNoInteractions(tickets);
    }

    @Test void replacementIsNamedButNeverLinkedFromTheOriginal() {
        TenantContext.setCurrentTenant("kebab");
        FiscalTicket original = mock(FiscalTicket.class), replacement = mock(FiscalTicket.class);
        when(tickets.findById("one")).thenReturn(Optional.of(original));
        when(tickets.corrections("one")).thenReturn(List.of(replacement));
        when(replacement.kind()).thenReturn(TicketKind.REPLACEMENT);
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.ticket("one", signatures.sign("kebab", "one"), new MockHttpServletRequest(), response, model);
        assertThat(model.get("guestCorrection")).isSameAs(replacement);
        assertThat(model).doesNotContainKey("guestCorrectionUrl");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test void refundedTicketHasAnIndependentlySignedCorrectionLink() {
        TenantContext.setCurrentTenant("kebab");
        FiscalTicket original = mock(FiscalTicket.class), refund = mock(FiscalTicket.class);
        when(tickets.findById("one")).thenReturn(Optional.of(original));
        when(tickets.corrections("one")).thenReturn(List.of(refund));
        when(refund.kind()).thenReturn(TicketKind.RECTIFYING);
        when(refund.id()).thenReturn("refund");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TenantRoutingResolver.TENANT_URL_PREFIX, "/kebab");
        ExtendedModelMap model = new ExtendedModelMap();
        controller.ticket("one", signatures.sign("kebab", "one"), request, new MockHttpServletResponse(), model);
        assertThat(model.get("guestCorrectionUrl")).isEqualTo("/kebab/ticket/refund?s=" + signatures.sign("kebab", "refund"));
    }

    @Test void sessionOnlyReceivesItsOwnTicketOnHostAndPrefixedRoutes() {
        TenantContext.setCurrentTenant("kebab");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("orderTenant", "kebab"); session.setAttribute("orderId", "order-1");
        when(orders.findById("order-1")).thenReturn(Optional.of(mock(Order.class)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(controller.status(session, request, new MockHttpServletResponse()).getBody())
                .containsEntry("state", "pending");
        FiscalTicket ticket = mock(FiscalTicket.class);
        when(ticket.issuedBy()).thenReturn("online"); when(ticket.id()).thenReturn("ticket-1");
        when(ticket.fullNumber()).thenReturn("TEST-T-000001");
        when(tickets.findForOrder("order-1")).thenReturn(Optional.of(ticket));
        assertThat(controller.status(session, request, new MockHttpServletResponse()).getBody().get("ticketUrl"))
                .startsWith("/ticket/ticket-1?s=v1.");
        assertThat(controller.status(session, request, new MockHttpServletResponse()).getBody())
                .containsEntry("number", "TEST-T-000001");
        request.setAttribute(TenantRoutingResolver.TENANT_URL_PREFIX, "/kebab");
        assertThat(controller.status(session, request, new MockHttpServletResponse()).getBody().get("ticketUrl"))
                .startsWith("/kebab/ticket/ticket-1?s=v1.");
        TenantContext.setCurrentTenant("other");
        assertThat(controller.status(session, request, new MockHttpServletResponse()).getStatusCode().value()).isEqualTo(404);
    }
}
