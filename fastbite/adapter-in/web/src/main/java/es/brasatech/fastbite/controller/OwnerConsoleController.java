package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.ProductionOnly;
import es.brasatech.fastbite.application.tenant.TenantSignupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import es.brasatech.fastbite.service.StripeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
@Slf4j
public class OwnerConsoleController {

    private final TenantLocationService tenantLocationService;
    private final TenantSignupService tenantSignupService;
    private final es.brasatech.fastbite.config.TenantRoutingResolver tenantResolver;
    private final StripeService stripeService;

    @Value("${fastbite.public-url:http://localhost:8080}")
    private String publicUrl;

    @ProductionOnly
    @PostMapping("/owner/add-location")
    public String addLocation(
            @RequestParam String tenantId,
            @RequestParam(defaultValue = "RESTAURANT") String plan,
            Principal principal,
            org.springframework.web.servlet.mvc.support.RedirectAttributes model) {
        if (principal == null) {
            return "redirect:/login";
        }
        String ownerUsername = principal.getName();
        try {
            tenantSignupService.registerAdditionalLocation(tenantId, ownerUsername, "RESTAURANT");
            return "redirect:/owner/console?location=" + tenantId.trim().toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            log.error("Failed to add location: " + tenantId, e);
            model.addFlashAttribute("error", e.getMessage());
            return "redirect:/owner/console?view=locations";
        }
    }

    @ProductionOnly
    @PostMapping("/owner/delete-location")
    public String deleteLocation(
            @RequestParam String tenantId,
            Principal principal) {
        if (principal == null) {
            return "redirect:/login";
        }
        String ownerUsername = principal.getName();
        if (tenantLocationService.isOwnerOf(ownerUsername, tenantId)) {
            // Keep the owner-to-location mapping so billing, cancellation and data export stay reachable.
            return "redirect:/owner/billing/" + tenantId;
        }
        return "redirect:/owner/console?view=locations";
    }

    @ProductionOnly
    @PostMapping("/owner/bind-domain")
    public String bindDomain(
            @RequestParam String tenantId,
            @RequestParam(required = false) String customDomain,
            Principal principal,
            org.springframework.web.servlet.mvc.support.RedirectAttributes model) {
        if (principal == null) {
            return "redirect:/login";
        }
        String ownerUsername = principal.getName();
        try {
            if (!tenantLocationService.isOwnerOf(ownerUsername, tenantId)) throw new IllegalArgumentException("You do not own this location.");
            // Evict old cache key
            tenantLocationService.getLocation(tenantId).ifPresent(loc -> {
                if (loc.customDomain() != null) {
                    tenantResolver.evictCache(loc.customDomain());
                }
            });
            
            // Bind domain
            tenantLocationService.bindCustomDomain(ownerUsername, tenantId, customDomain);
            
            // Evict new cache key just in case
            if (customDomain != null) {
                tenantResolver.evictCache(customDomain);
            }
            
            return "redirect:/owner/console?view=location-settings&section=domain&location=" + tenantId;
        } catch (Exception e) {
            log.error("Failed to bind custom domain: " + customDomain, e);
            model.addFlashAttribute("error", e.getMessage());
            return "redirect:/owner/console?view=locations";
        }
    }

    @PostMapping("/owner/update-stripe-account")
    public String updateStripeAccount(
            @RequestParam String tenantId,
            @RequestParam(required = false) String stripeAccountId,
            Principal principal,
            org.springframework.web.servlet.mvc.support.RedirectAttributes model) {
        if (principal == null) {
            return "redirect:/login";
        }
        String ownerUsername = principal.getName();
        try {
            tenantLocationService.updateStripeAccountId(ownerUsername, tenantId, stripeAccountId);
            refreshStripeStatus(stripeAccountId);
            return "redirect:/owner/console?view=location-settings&section=payments&location=" + tenantId;
        } catch (Exception e) {
            log.error("Failed to update Stripe account ID for location: " + tenantId, e);
            model.addFlashAttribute("error", e.getMessage());
            return "redirect:/owner/console?view=locations";
        }
    }

    @PostMapping("/owner/connect-stripe")
    public String connectStripe(
            @RequestParam String tenantId,
            Principal principal,
            jakarta.servlet.http.HttpServletRequest request,
            org.springframework.web.servlet.mvc.support.RedirectAttributes model) {
        if (principal == null) {
            return "redirect:/login";
        }
        String ownerUsername = principal.getName();
        if (!tenantLocationService.isOwnerOf(ownerUsername, tenantId)) {
            model.addFlashAttribute("error", "You do not own this location.");
            return "redirect:/owner/console?view=locations";
        }
        if (!stripeService.isConfigured()) {
            model.addFlashAttribute("error", "Online payment platform is not configured by the administrator.");
            return "redirect:/owner/console?view=location-settings&section=payments&location=" + tenantId;
        }

        try {
            var locationOpt = tenantLocationService.getLocation(tenantId);
            String existingAccountId = locationOpt.map(es.brasatech.fastbite.domain.tenant.TenantLocation::stripeAccountId).orElse(null);
            String accountId = existingAccountId;
            if (accountId == null || accountId.isBlank()) {
                com.stripe.model.Account account = stripeService.createConnectedAccount(tenantId, null, tenantId);
                accountId = account.getId();
                tenantLocationService.updateStripeAccountId(ownerUsername, tenantId, accountId);
            }

            String origin = resolveOrigin(request);
            String refreshUrl = origin + "/owner/connect-stripe/refresh?tenantId=" + tenantId;
            String returnUrl = origin + "/owner/connect-stripe/return?tenantId=" + tenantId;

            com.stripe.model.AccountLink accountLink = stripeService.createAccountLink(accountId, refreshUrl, returnUrl);
            return "redirect:" + accountLink.getUrl();
        } catch (Exception e) {
            log.error("Failed to initiate Stripe onboarding for location: " + tenantId, e);
            model.addFlashAttribute("error", "Failed to connect with Stripe: " + e.getMessage());
            return "redirect:/owner/console?view=location-settings&section=payments&location=" + tenantId;
        }
    }

    @GetMapping({"/owner/connect-stripe/return", "/owner/connect-stripe/refresh"})
    public String connectStripeReturn(
            @RequestParam String tenantId,
            Principal principal,
            org.springframework.web.servlet.mvc.support.RedirectAttributes model) {
        if (principal == null) {
            return "redirect:/login";
        }
        var locationOpt = tenantLocationService.getLocation(tenantId);
        String accountId = locationOpt.map(es.brasatech.fastbite.domain.tenant.TenantLocation::stripeAccountId).orElse(null);
        refreshStripeStatus(accountId);
        model.addFlashAttribute("success", "Stripe connection updated for " + tenantId);
        return "redirect:/owner/console?view=location-settings&section=payments&location=" + tenantId;
    }

    private void refreshStripeStatus(String stripeAccountId) {
        if (stripeAccountId == null || stripeAccountId.isBlank() || !stripeService.isConfigured()) {
            return;
        }
        try {
            com.stripe.model.Account account = stripeService.retrieveAccount(stripeAccountId.trim());
            if (account != null) {
                boolean chargesEnabled = Boolean.TRUE.equals(account.getChargesEnabled());
                boolean detailsSubmitted = Boolean.TRUE.equals(account.getDetailsSubmitted());
                tenantLocationService.syncStripeCapabilities(stripeAccountId.trim(), chargesEnabled, detailsSubmitted);
                log.info("Refreshed Stripe Connect account {} capabilities: chargesEnabled={}, detailsSubmitted={}",
                        stripeAccountId, chargesEnabled, detailsSubmitted);
            }
        } catch (Exception e) {
            log.warn("Failed to refresh Stripe account status for {}: {}", stripeAccountId, e.getMessage());
        }
    }

    private String resolveOrigin(jakarta.servlet.http.HttpServletRequest request) {
        if (publicUrl != null && !publicUrl.isBlank()) {
            return publicUrl.replaceAll("/+$", "");
        }
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int port = request.getServerPort();
        boolean standardPort = ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
        return standardPort ? (scheme + "://" + serverName) : (scheme + "://" + serverName + ":" + port);
    }
}
