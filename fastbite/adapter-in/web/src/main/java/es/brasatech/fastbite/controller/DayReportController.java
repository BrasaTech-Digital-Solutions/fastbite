package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.fiscal.FiscalTicketService;
import es.brasatech.fastbite.application.settings.RestaurantSettingsService;
import es.brasatech.fastbite.domain.fiscal.DayReport;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The end-of-day report and the cash check, both worked out from the day's tickets each time they are
 * asked for. Nothing counted is stored: the cashier compares the drawer with what the tickets say.
 */
@Controller
@RequiredArgsConstructor
public class DayReportController {

    /** Who handles the till: the kitchen and waiters have no business with the day's takings. */
    private static final Set<String> CASH_ROLES = Set.of("ROLE_CASHIER", "ROLE_MANAGER", "ROLE_ADMIN", "ROLE_OWNER");

    private final FiscalTicketService tickets;
    private final FiscalProfileService profiles;
    private final RestaurantSettingsService settings;

    @GetMapping({"/{tenantId}/counter/day-report", "/counter/day-report"})
    public String report(@PathVariable(required = false) String tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication user, Model model) {
        requireTill(user);
        LocalDate today = tickets.today();
        LocalDate day = date != null && !date.isAfter(today) ? date : today;
        model.addAttribute("report", tickets.dayReport(day));
        model.addAttribute("day", day);
        model.addAttribute("today", today);
        model.addAttribute("printedAt", today.atTime(tickets.now()));
        model.addAttribute("business", profiles.get());
        model.addAttribute("training", tickets.training());
        model.addAttribute("tenantPrefix", tenantId != null ? "/" + tenantId : "");
        return "fastfood/dayReport";
    }

    /** What the cash check needs: the cash today's tickets put in the drawer, and where the day started. */
    @ResponseBody
    @GetMapping({"/{tenantId}/counter/api/cash", "/counter/api/cash"})
    public Map<String, Object> cash(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication user) {
        requireTill(user);
        LocalDate today = tickets.today();
        LocalDate day = date != null && !date.isAfter(today) ? date : today;
        DayReport report = tickets.dayReport(day);
        Map<String, Object> cash = new LinkedHashMap<>();
        cash.put("day", day.toString());
        cash.put("cashFloat", settings.cashFloat());
        cash.put("cashSales", report.cashSales());
        cash.put("cashRefunds", report.cashRefunds());
        cash.put("cashNet", report.cashNet());
        // Today's figures are as of now; a past day's are final
        cash.put("asOf", day.equals(today) ? tickets.now().format(DateTimeFormatter.ofPattern("HH:mm")) : null);
        return cash;
    }

    /** Whether the user handles the till, so the counter and the tickets page offer the cash check. */
    public static boolean handlesTill(Authentication user) {
        return user != null && user.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(CASH_ROLES::contains);
    }

    private static void requireTill(Authentication user) {
        if (!handlesTill(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
