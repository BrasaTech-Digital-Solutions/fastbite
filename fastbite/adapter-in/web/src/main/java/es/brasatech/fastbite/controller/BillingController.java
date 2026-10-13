package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.tenant.SubscriptionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.security.Principal;

/** Subscriptions and billing: production's alone when the environments are linked. */
@Controller
@es.brasatech.fastbite.config.ProductionOnly
public class BillingController {
    private final SubscriptionService subscriptions;
    private final es.brasatech.fastbite.application.tenant.TenantLocationService locations;
    public BillingController(SubscriptionService subscriptions, es.brasatech.fastbite.application.tenant.TenantLocationService locations) { this.subscriptions=subscriptions; this.locations=locations; }
    @GetMapping("/owner/billing/{tenant}")
    public String show(@PathVariable String tenant, Principal owner, Model model) {
        try { model.addAttribute("billing", subscriptions.account(tenant, owner.getName())); }
        catch (IllegalArgumentException e) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND); }
        model.addAttribute("billingConfigured", subscriptions.configured());
        model.addAttribute("ownerUsername", owner.getName());
        model.addAttribute("locations", locations.getLocationsByOwner(owner.getName()));
        model.addAttribute("selected", locations.getLocation(tenant).orElseThrow());
        model.addAttribute("view", "settings");
        return "fastfood/owner/billing";
    }
    @PostMapping("/owner/billing/{tenant}/{action}")
    public String action(@PathVariable String tenant, @PathVariable String action, Principal owner, RedirectAttributes flash) {
        try {
            return switch (action) {
                case "checkout" -> "redirect:" + subscriptions.checkout(tenant, owner.getName());
                case "portal" -> "redirect:" + subscriptions.portal(tenant, owner.getName());
                case "refresh" -> { subscriptions.refresh(tenant, owner.getName()); yield "redirect:/owner/billing/" + tenant; }
                default -> throw new IllegalArgumentException("Unknown billing action.");
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/owner/billing/" + tenant;
        }
    }
    @PostMapping("/owner/group-quote")
    public String groupQuote(@RequestParam String email, @RequestParam int locations, Principal owner, RedirectAttributes flash) {
        try { subscriptions.requestGroupQuote(owner.getName(), email, locations); flash.addFlashAttribute("notice", "Your group pricing request has been saved. We will use your email to follow up."); }
        catch (IllegalArgumentException e) { flash.addFlashAttribute("error", e.getMessage()); }
        return "redirect:/owner/console?view=settings";
    }


    @PostMapping("/owner/billing/{tenant}/override-plan")
    public String overridePlan(@PathVariable String tenant, @RequestParam String plan, RedirectAttributes flash) {
        try {
            subscriptions.updateTenantPlan(tenant, plan);
            flash.addFlashAttribute("notice", "Plan for " + tenant + " updated to " + plan + ".");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/owner/billing/" + tenant;
    }

    @PostMapping("/owner/billing/{tenant}/extend-trial")
    public String extendTrial(@PathVariable String tenant, @RequestParam int days, RedirectAttributes flash) {
        try {
            subscriptions.extendTenantTrial(tenant, days);
            flash.addFlashAttribute("notice", "Trial for " + tenant + " extended by " + days + " days.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/owner/billing/" + tenant;
    }
}
