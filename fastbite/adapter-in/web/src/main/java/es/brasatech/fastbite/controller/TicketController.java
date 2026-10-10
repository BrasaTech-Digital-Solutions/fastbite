package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.fiscal.FiscalTicketService;
import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.PaymentMethod;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.dto.counter.InvoiceDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tickets at the counter: taking payment (which issues the ticket), printing, the list of the
 * day's tickets, full invoices on request and refunds.
 */
@Controller
@RequiredArgsConstructor
public class TicketController {

    /** Roles that may refund: giving money back is a manager's decision. */
    private static final Set<String> REFUNDERS = Set.of("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_OWNER");

    private final FiscalTicketService tickets;

    /** Payment of one or more orders at the counter. */
    public record SettleRequest(List<String> orderIds, String paymentMethod, BigDecimal cashReceived,
            InvoiceDetails invoice) {
    }

    public record RefundRequest(String reason) {
    }

    @ResponseBody
    @PostMapping("/counter/api/settle")
    public Map<String, Object> settle(@RequestBody SettleRequest request, Authentication user) {
        FiscalTicket ticket = tickets.settle(request.orderIds(),
                FiscalTicketService.Payment.of(PaymentMethod.of(request.paymentMethod()), request.cashReceived()),
                request.invoice() == null ? null : request.invoice().toRecipient(), user.getName());
        return summary(ticket);
    }

    @ResponseBody
    @PostMapping("/counter/api/tickets/{ticketId}/invoice")
    public Map<String, Object> invoice(@PathVariable String ticketId, @RequestBody InvoiceDetails request,
            Authentication user) {
        return summary(tickets.replace(ticketId, request.toRecipient(), user.getName()));
    }

    @ResponseBody
    @PostMapping("/counter/api/tickets/{ticketId}/refund")
    public Map<String, Object> refund(@PathVariable String ticketId, @RequestBody RefundRequest request,
            Authentication user) {
        if (!mayRefund(user)) {
            throw new LocalizedRejection("ticket.error.refundRole", "Only a manager can refund tickets");
        }
        return summary(tickets.refund(ticketId, request.reason(), user.getName()));
    }

    /** The ticket as printed; the counter loads it into a hidden frame and prints it. */
    @GetMapping({"/{tenantId}/counter/tickets/{ticketId}", "/counter/tickets/{ticketId}"})
    public String print(@PathVariable String ticketId, Model model) {
        FiscalTicket ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("ticket", ticket);
        return "fastfood/ticket";
    }

    /** The tickets of a day, with what can still be done with each. */
    @GetMapping({"/{tenantId}/counter/tickets", "/counter/tickets"})
    public String list(@PathVariable(required = false) String tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication user, Model model) {
        LocalDate day = date != null ? date : tickets.today();
        List<FiscalTicket> issued = tickets.issuedOn(day);
        Map<String, String> correctedBy = new LinkedHashMap<>();
        issued.stream().filter(ticket -> ticket.corrects() != null)
                .forEach(ticket -> correctedBy.put(ticket.corrects().ticketId(), ticket.fullNumber()));
        // Corrections issued on a later day still change what can be done with an earlier ticket
        issued.stream().filter(ticket -> !correctedBy.containsKey(ticket.id()))
                .forEach(ticket -> tickets.corrections(ticket.id()).stream().findFirst()
                        .ifPresent(correction -> correctedBy.put(ticket.id(), correction.fullNumber())));

        Map<PaymentMethod, BigDecimal> byMethod = new EnumMap<>(PaymentMethod.class);
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal tips = BigDecimal.ZERO;
        for (FiscalTicket ticket : issued) {
            if (ticket.kind() == TicketKind.REPLACEMENT) {
                continue; // replaces a ticket already counted
            }
            byMethod.merge(ticket.paymentMethod(), ticket.charged(), BigDecimal::add);
            net = net.add(ticket.total());
            tips = tips.add(ticket.tip());
        }
        model.addAttribute("day", day);
        model.addAttribute("today", tickets.today());
        model.addAttribute("tickets", issued.reversed());
        model.addAttribute("correctedBy", correctedBy);
        model.addAttribute("byMethod", byMethod);
        model.addAttribute("net", net);
        model.addAttribute("tips", tips);
        model.addAttribute("training", tickets.training());
        model.addAttribute("mayRefund", mayRefund(user));
        model.addAttribute("tenantPrefix", tenantId != null ? "/" + tenantId : "");
        return "fastfood/tickets";
    }

    private static boolean mayRefund(Authentication user) {
        return user != null && user.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(REFUNDERS::contains);
    }

    static Map<String, Object> summary(FiscalTicket ticket) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketId", ticket.id());
        result.put("fullNumber", ticket.fullNumber());
        result.put("kind", ticket.kind().name());
        result.put("total", ticket.total());
        result.put("change", ticket.change());
        result.put("training", ticket.training());
        return result;
    }
}
