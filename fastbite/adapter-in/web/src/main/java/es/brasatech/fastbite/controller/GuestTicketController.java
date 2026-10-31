package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.fiscal.FiscalTicketService;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.service.GuestTicketSignature;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/** Public bearer links, scoped to a restaurant and a ticket. */
@Controller
@RequiredArgsConstructor
public class GuestTicketController {
    private final FiscalTicketService tickets;
    private final OrderService orders;
    private final GuestTicketSignature signatures;
    private final TicketController ticketController;

    private static void privateHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
    }

    private String url(String prefix, String tenant, String ticket) {
        return prefix + "/ticket/" + UriUtils.encodePathSegment(ticket, StandardCharsets.UTF_8)
                + "?s=" + signatures.sign(tenant, ticket);
    }

    @GetMapping({"/{tenantId}/ticket/{ticketId}", "/ticket/{ticketId}"})
    public String ticket(@PathVariable String ticketId, @RequestParam(name = "s", required = false) String signature,
            HttpServletRequest request, HttpServletResponse response, Model model) {
        privateHeaders(response);
        String tenant = TenantContext.getCurrentTenant();
        if (!signatures.valid(tenant, ticketId, signature)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        FiscalTicket ticket = tickets.findById(ticketId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("guestTicket", true);
        Object attribute = request.getAttribute(TenantRoutingResolver.TENANT_URL_PREFIX);
        String prefix = attribute == null ? "" : attribute.toString();
        tickets.corrections(ticketId).stream().findFirst().ifPresent(correction -> {
            model.addAttribute("guestCorrection", correction);
            // A simplified ticket must never grant access to a replacement with recipient data.
            if (correction.kind() != TicketKind.REPLACEMENT) {
                model.addAttribute("guestCorrectionUrl", url(prefix, tenant, correction.id()));
            }
        });
        return ticketController.print(ticketId, model);
    }

    /** Only the order stored in this browser's session can yield a link. */
    @ResponseBody
    @GetMapping({"/{tenantId}/api/guest-ticket", "/api/guest-ticket"})
    public ResponseEntity<Map<String, String>> status(HttpSession session, HttpServletRequest request, HttpServletResponse response) {
        privateHeaders(response);
        String tenant = TenantContext.getCurrentTenant();
        String sessionTenant = (String) session.getAttribute("orderTenant");
        String orderId = (String) session.getAttribute("orderId");
        if (tenant == null || sessionTenant == null || !tenant.equalsIgnoreCase(sessionTenant) || orderId == null
                || orders.findById(orderId).isEmpty()) return ResponseEntity.notFound().build();
        Optional<FiscalTicket> ticket = tickets.findForOrder(orderId)
                .filter(t -> "online".equals(t.issuedBy()));
        if (ticket.isEmpty()) return ResponseEntity.ok(Map.of("state", "pending"));
        Object attribute = request.getAttribute(TenantRoutingResolver.TENANT_URL_PREFIX);
        String prefix = attribute == null ? "" : attribute.toString();
        return ResponseEntity.ok(Map.of("state", "ready", "ticketUrl", url(prefix, tenant, ticket.get().id()), "number", ticket.get().fullNumber()));
    }
}
