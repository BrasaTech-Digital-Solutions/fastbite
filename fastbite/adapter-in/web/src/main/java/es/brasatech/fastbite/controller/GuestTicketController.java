package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.fiscal.FiscalTicketService;
import es.brasatech.fastbite.application.fiscal.GuestInvoiceRateLimit;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import es.brasatech.fastbite.dto.counter.InvoiceDetails;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.service.GuestTicketSignature;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
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
    private final GuestInvoiceRateLimit invoiceLimit;
    private final MessageSource messages;

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
        List<FiscalTicket> corrections = tickets.corrections(ticketId);
        boolean invoiceAvailable = ticket.kind() == TicketKind.SIMPLIFIED && corrections.isEmpty()
                && tickets.cancellation(ticketId).isEmpty();
        if (invoiceAvailable) {
            // Resolve deferred CSRF while the response can still set the new session cookie.
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) token.getToken();
            model.addAttribute("guestInvoiceAction", prefix + "/ticket/" + UriUtils.encodePathSegment(ticketId, StandardCharsets.UTF_8)
                    + "/invoice?s=" + signatures.sign(tenant, ticketId));
        }
        corrections.stream().findFirst().ifPresent(correction -> {
            model.addAttribute("guestCorrection", correction);
            // A simplified ticket must never grant access to a replacement with recipient data.
            if (correction.kind() != TicketKind.REPLACEMENT) {
                model.addAttribute("guestCorrectionUrl", url(prefix, tenant, correction.id()));
            }
        });
        return ticketController.print(ticketId, model);
    }

    /** The signed original authorizes one replacement; the new invoice gets its own link. */
    @PostMapping({"/{tenantId}/ticket/{ticketId}/invoice", "/ticket/{ticketId}/invoice"})
    public String requestInvoice(@PathVariable String ticketId, @RequestParam(name = "s", required = false) String signature,
            @RequestParam(required = false) String name, @RequestParam(required = false) String taxId,
            @RequestParam(required = false) String country, @RequestParam(required = false) String address,
            HttpServletRequest request, HttpServletResponse response, Model model, Locale locale) {
        privateHeaders(response);
        String tenant = TenantContext.getCurrentTenant();
        if (!signatures.valid(tenant, ticketId, signature) || tickets.findById(ticketId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String ticketKey = signatures.sign(tenant, "invoice-ticket:" + ticketId);
        String clientKey = signatures.sign("guest-invoice-client", request.getRemoteAddr());
        if (!invoiceLimit.tryAcquire(ticketKey, clientKey)) {
            response.setStatus(429);
            model.addAttribute("invoiceError", messages.getMessage("guest.invoice.limit", null, locale));
            return ticket(ticketId, signature, request, response, model);
        }
        model.addAttribute("invoiceName", name);
        model.addAttribute("invoiceTaxId", taxId);
        model.addAttribute("invoiceCountry", country);
        model.addAttribute("invoiceAddress", address);
        try {
            FiscalTicket invoice = tickets.replace(ticketId, new InvoiceDetails(taxId, country, name, address).toRecipient(), "cliente online");
            Object attribute = request.getAttribute(TenantRoutingResolver.TENANT_URL_PREFIX);
            String prefix = attribute == null ? "" : attribute.toString();
            return "redirect:" + url(prefix, tenant, invoice.id());
        } catch (LocalizedRejection rejection) {
            response.setStatus(400);
            model.addAttribute("invoiceError", messages.getMessage(rejection.key(), rejection.args(), rejection.getMessage(), locale));
            String field = switch (rejection.key()) {
                case "recipient.error.name" -> "name";
                case "recipient.error.taxId", "recipient.error.taxIdInvalid" -> "taxId";
                case "recipient.error.country" -> "country";
                case "recipient.error.length" -> name != null && name.strip().length() > 120 ? "name" : "address";
                default -> "form";
            };
            model.addAttribute("invoiceErrorField", field);
            return ticket(ticketId, signature, request, response, model);
        }
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
