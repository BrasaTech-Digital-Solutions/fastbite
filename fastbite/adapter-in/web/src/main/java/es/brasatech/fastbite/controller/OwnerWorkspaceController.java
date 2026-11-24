package es.brasatech.fastbite.controller;
import es.brasatech.fastbite.application.tenant.*;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationService;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationService.RevokeResult;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationService.StartSigningResult;
import es.brasatech.fastbite.config.ProductionOnly;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.fiscal.SpanishTaxId;
import es.brasatech.fastbite.domain.fiscal.SpanishProvinces;
import es.brasatech.fastbite.domain.fiscal.VatRates;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.domain.user.Role;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorization;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorizationStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Year;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import es.brasatech.fastbite.domain.tenant.BillingAccount;
import java.util.*;
@Controller
public class OwnerWorkspaceController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OwnerWorkspaceController.class);
    private final TenantLocationService locations;
    private final OwnerWorkspaceService workspace;
    private final OwnerStaffService staff;
    private final SubscriptionService subscriptions;
    private final TenantBackupRestorePort backups;
    private final es.brasatech.fastbite.application.platform.PromotionService promotionService;
    private final String ordersTimeZone;
    private final AppearancePort appearance;
    private final es.brasatech.fastbite.config.ThemeCatalog themes;
    private final MessageSource messages;
    private final es.brasatech.fastbite.application.platform.PlatformEnvironment environment;
    private final es.brasatech.fastbite.application.platform.PlatformLink link;
    private final FreePlanLock freePlanLock;
    private final es.brasatech.fastbite.application.platform.PlatformAdmins admins;
    private final LegalAcceptancePort acceptances;
    private final es.brasatech.fastbite.application.settings.GuestPrivacyNoticeService privacyNotices;
    private final java.time.Clock clock;
    private final String termsVersion;
    private final VerifactuAuthorizationService verifactuAuthorizations;
    private final LocationLifecycleService locationLifecycle;
    private final TenantRoutingResolver routingResolver;
    public OwnerWorkspaceController(TenantLocationService locations,OwnerWorkspaceService workspace,OwnerStaffService staff,SubscriptionService subscriptions,TenantBackupRestorePort backups,AppearancePort appearance,es.brasatech.fastbite.config.ThemeCatalog themes,
            MessageSource messages, es.brasatech.fastbite.application.platform.PlatformEnvironment environment,
            es.brasatech.fastbite.application.platform.PlatformLink link, FreePlanLock freePlanLock,
            es.brasatech.fastbite.application.platform.PlatformAdmins admins,
            LegalAcceptancePort acceptances,
            es.brasatech.fastbite.application.settings.GuestPrivacyNoticeService privacyNotices,
            java.time.Clock clock,
            @org.springframework.beans.factory.annotation.Value("${fastbite.legal.updated:27/10/2026}") String termsVersion,
            es.brasatech.fastbite.application.platform.PromotionService promotionService,
            @org.springframework.beans.factory.annotation.Value("${fastbite.orders.time-zone:Europe/Madrid}") String ordersTimeZone,
            ObjectProvider<VerifactuAuthorizationService> verifactuAuthorizations,
            ObjectProvider<LocationLifecycleService> locationLifecycle,
            ObjectProvider<TenantRoutingResolver> routingResolver) {
        this.locations=locations;this.workspace=workspace;this.staff=staff;this.subscriptions=subscriptions;this.backups=backups;
        this.appearance=appearance;this.themes=themes;this.messages=messages;this.environment=environment;this.link=link;
        this.freePlanLock=java.util.Objects.requireNonNull(freePlanLock, "freePlanLock");
        this.admins=java.util.Objects.requireNonNull(admins, "admins");
        this.acceptances=java.util.Objects.requireNonNull(acceptances, "acceptances");
        this.privacyNotices=java.util.Objects.requireNonNull(privacyNotices, "privacyNotices");
        this.clock=java.util.Objects.requireNonNull(clock, "clock");
        this.termsVersion=java.util.Objects.requireNonNull(termsVersion, "termsVersion");
        this.promotionService=java.util.Objects.requireNonNull(promotionService, "promotionService");
        this.ordersTimeZone = (ordersTimeZone != null && !ordersTimeZone.isBlank()) ? ordersTimeZone : "Europe/Madrid";
        this.verifactuAuthorizations = verifactuAuthorizations != null ? verifactuAuthorizations.getIfAvailable() : null;
        this.locationLifecycle = locationLifecycle != null ? locationLifecycle.getIfAvailable() : null;
        this.routingResolver = routingResolver != null ? routingResolver.getIfAvailable() : null;
    }
    @GetMapping("/owner/console")
    public String show(Principal owner,Model model,@RequestParam(defaultValue="overview") String view,
            @RequestParam(required=false) String location,@RequestParam(defaultValue="general") String section,
            @RequestParam(defaultValue="false") boolean opening, Locale locale) {
        if(owner==null) return "redirect:/login";
        if(!Set.of("overview","locations","team","location-settings","settings", "restaurants", "billing", "account", "today", "menu", "tickets", "location-config", "data").contains(view)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(!Set.of("general","legal","appearance","payments","domain","backup","advanced").contains(section)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var list=locations.getLocationsByOwner(owner.getName()).stream().sorted(Comparator.comparing(es.brasatech.fastbite.domain.tenant.TenantLocation::tenantId)).toList();
        String requestedView = view;
        String area = switch (view) {
            case "locations", "restaurants" -> "restaurants";
            case "settings", "account" -> "account";
            case "billing" -> "billing";
            case "team" -> "team";
            case "menu" -> "menu";
            case "tickets" -> "tickets";
            case "location-config" -> "location-config";
            case "data" -> "data";
            case "location-settings" -> switch (section) {
                case "legal" -> "tickets";
                case "backup", "advanced" -> "data";
                default -> "location-config";
            };
            default -> "today";
        };
        boolean global = Set.of("restaurants", "billing", "account").contains(area);
        var selected = location == null ? (global || list.isEmpty() ? null : list.getFirst())
                : list.stream().filter(l -> l.tenantId().equals(location)).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // Global account pages do not imply a selected location; explicit legacy location links remain authorized above.
        if (global) selected = null;
        if (location == null && requestedView.equals("overview")) {
            if (list.size() == 1 && !list.getFirst().isClosed() && !subscriptions.isClosed(list.getFirst().tenantId()) && !locations.isClosed(list.getFirst().tenantId())) selected = list.getFirst();
            else { selected = null; area = "restaurants"; }
        }
        if (area.equals("restaurants") || area.equals("billing")) {
            Map<String, OwnerWorkspaceService.Summary> locationSummaries = new LinkedHashMap<>();
            Map<String, String> locationBilling = new LinkedHashMap<>();
            for (var loc : list) {
                if (area.equals("restaurants") && !loc.isClosed() && !subscriptions.isClosed(loc.tenantId()) && !locations.isClosed(loc.tenantId())) {
                    var locationSummary = workspace.summary(owner.getName(), loc.tenantId());
                    if (locationSummary != null) locationSummaries.put(loc.tenantId(), locationSummary);
                }
                if (area.equals("billing")) {
                    try {
                        var account = subscriptions.account(loc.tenantId(), owner.getName());
                        if (account != null) locationBilling.put(loc.tenantId(), localizedBillingStatus(account, locale, messages, clock, ordersTimeZone));
                    } catch (IllegalArgumentException e) {
                        log.warn("Billing status unavailable for location {}", loc.tenantId());
                    }
                }
            }
            model.addAttribute("locationSummaries", locationSummaries);
            model.addAttribute("locationBilling", locationBilling);
        }
        view = switch (area) {
            case "restaurants" -> "locations";
            case "account", "billing" -> "settings";
            case "team" -> "team";
            case "tickets", "location-config", "data" -> "location-settings";
            default -> "overview";
        };
        if (requestedView.equals("tickets")) section = "legal";
        if (requestedView.equals("data") && !Set.of("backup", "advanced").contains(section)) section = "backup";
        if (requestedView.equals("location-config") && !Set.of("general", "appearance", "payments", "domain").contains(section)) section = "general";
        Map<String, String> closedLabels = new LinkedHashMap<>();
        for (var loc : list) {
            if (loc.closedAt() != null) {
                String date = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.of(ordersTimeZone)).format(loc.closedAt());
                closedLabels.put(loc.tenantId(), messages.getMessage("workspace.closed.on", new Object[]{date}, "Closed on " + date, locale));
            }
        }
        model.addAttribute("closedLabels", closedLabels);
        model.addAttribute("ownerUsername",owner.getName());model.addAttribute("locations",list);
        model.addAttribute("platformAdmin",environment.production() && admins.isAdmin(owner.getName()));
        model.addAttribute("marketingConsent",acceptances.mayReceiveOffers(owner.getName()));
        model.addAttribute("selected",selected);model.addAttribute("view",view);model.addAttribute("section",section);model.addAttribute("workspaceArea",area);
        model.addAttribute("showOpening", opening);
        model.addAttribute("themes",themes.all());
        model.addAttribute("ownerTheme",themes.resolve(appearance.ownerTheme(owner.getName())));
        model.addAttribute("demoTemplates",backups.getAvailableTemplates(locale!=null?locale:Locale.getDefault()));
        if(selected!=null) {
            var billing=subscriptions.account(selected.tenantId(),owner.getName());
            model.addAttribute("billing",billing);
            model.addAttribute("billingStatusLabel", localizedBillingStatus(billing, locale, messages, clock, ordersTimeZone));
            Instant trialEnd = trialEnd(billing);
            model.addAttribute("trialEndsSoon", trialEnd != null && trialEnd.isAfter(clock.instant())
                    && !trialEnd.isAfter(clock.instant().plus(7, ChronoUnit.DAYS)));
            boolean locationClosed=(billing!=null && billing.isClosed()) || selected.isClosed() || subscriptions.isClosed(selected.tenantId()) || locations.isClosed(selected.tenantId());
            model.addAttribute("locationClosed",locationClosed);
            if(locationClosed) {
                model.addAttribute("verifactu",workspace.verifactuSummary(owner.getName(),selected.tenantId(),environment.sandbox()));
                model.addAttribute("closedTickets",workspace.fiscalTickets(owner.getName(),selected.tenantId()));
                model.addAttribute("fiscal",workspace.fiscalProfile(owner.getName(),selected.tenantId()));
            } else {
                model.addAttribute("selectedTheme",themes.resolve(appearance.effectiveTheme(selected.tenantId())));
                model.addAttribute("themeOverride",appearance.locationTheme(selected.tenantId()));
                if (view.equals("overview") && verifactuAuthorizations != null
                        && (environment.sandbox() || !workspace.isOpened(owner.getName(), selected.tenantId()))) {
                    FiscalProfile currentFiscal = workspace.fiscalProfile(owner.getName(), selected.tenantId());
                    if (currentFiscal != null && currentFiscal.taxId() != null && !currentFiscal.taxId().isBlank()) {
                        verifactuAuthorizations.refreshForNifOnPageLoad(currentFiscal.taxId());
                    }
                }
                var setup=workspace.summary(owner.getName(),selected.tenantId());
                model.addAttribute("setup",setup);
                if (!environment.sandbox() && setup.opened() && billing != null && billing.trialNotStarted()) {
                    billing = subscriptions.account(selected.tenantId(), owner.getName());
                    model.addAttribute("billing", billing);
                    model.addAttribute("billingStatusLabel", localizedBillingStatus(billing, locale, messages, clock, ordersTimeZone));
                    Instant updatedTrialEnd = trialEnd(billing);
                    model.addAttribute("trialEndsSoon", updatedTrialEnd != null && updatedTrialEnd.isAfter(clock.instant())
                            && !updatedTrialEnd.isAfter(clock.instant().plus(7, ChronoUnit.DAYS)));
                }
                if (view.equals("overview")) {
                    model.addAttribute("verifactuStep", buildVerifactuStep(owner.getName(), selected.tenantId(), setup.fiscal()));
                    if (!environment.sandbox()) {
                        model.addAttribute("ordersNeedingRefund", workspace.ordersNeedingRefund(owner.getName(), selected.tenantId()));
                    }
                }
                if (view.equals("overview") && setup.opened() && !environment.sandbox()) {
                    model.addAttribute("todayVerifactu", workspace.verifactuSummary(owner.getName(), selected.tenantId(), false));
                }
                if(view.equals("location-settings") && section.equals("general")) {
                    model.addAttribute("cashFloat",workspace.cashFloat(owner.getName(),selected.tenantId()));
                    if (locationLifecycle != null) {
                        model.addAttribute("removalMode", locationLifecycle.removalAction(owner.getName(), selected.tenantId()).name());
                    }
                }
                if(view.equals("location-settings") && section.equals("backup")) {
                    promotionService.getUndoablePromotion(selected.tenantId(), owner.getName())
                            .ifPresent(undo -> {
                                model.addAttribute("undoablePromotion", undo);
                                if (undo.appliedAt() != null) {
                                    try {
                                        model.addAttribute("undoablePromotionAt", java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                                                .withZone(java.time.ZoneId.of(ordersTimeZone))
                                                .format(undo.appliedAt()));
                                    } catch (Exception e) {
                                        model.addAttribute("undoablePromotionAt", undo.appliedAt().toString());
                                    }
                                }
                            });
                }
                if(section.equals("legal")) {
                    addLegal(model,setup.fiscal(),selected.tenantId(),owner.getName());
                    model.addAttribute("fiscalLocked",workspace.fiscalLocked(owner.getName(),selected.tenantId()));
                    model.addAttribute("verifactu",workspace.verifactuSummary(owner.getName(),selected.tenantId(),environment.sandbox()));
                    model.addAttribute("issuedTickets", workspace.fiscalTickets(owner.getName(), selected.tenantId()));
                    model.addAttribute("activePrivacyNotice",privacyNotices.findActive(selected.tenantId()).orElse(null));
                    model.addAttribute("privacyNoticeHistory",privacyNotices.history(selected.tenantId()));
                }
                boolean freePlanLocked=freePlanLock.locked(selected.tenantId());
                model.addAttribute("freePlanLocked",freePlanLocked);
                model.addAttribute("freePlanPaymentsMissing",freePlanLocked && !selected.isStripeVerified());
                if(view.equals("team")) {
                    model.addAttribute("users",staff.listStaff(owner.getName(),selected.tenantId()).stream().filter(u->!u.isOwner()).toList());
                    model.addAttribute("tenantId",selected.tenantId());
                    model.addAttribute("roles",Arrays.stream(Role.values()).filter(r->r!=Role.OWNER).toList());
                }
            }
        }
        return "fastfood/owner/console";
    }
    private String workspaceMessage(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    private static Instant trialEnd(BillingAccount account) {
        if (account == null) return null;
        if ("LOCAL_TRIAL".equals(account.status()) && account.trialStartedAt() != null) return account.trialEndsAt();
        if ("trialing".equals(account.status()) && account.stripeTrialEnd() > 0) return Instant.ofEpochSecond(account.stripeTrialEnd());
        return null;
    }

    static String localizedBillingStatus(BillingAccount account, Locale locale, MessageSource messages, Clock clock, String ordersTimeZone) {
        if (account == null) return "";
        Instant end = trialEnd(account);
        if (end != null && end.isAfter(clock.instant())) {
            String date = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.of(ordersTimeZone)).format(end);
            return messages.getMessage("workspace.billing.trial.until", new Object[]{date}, "Free trial until " + date, locale);
        }
        String key;
        if (account.isClosed()) key = "closed";
        else if (account.isContractEnded()) key = "ended";
        else if (account.isFreePlan()) key = "free";
        else if (account.trialNotStarted()) key = "trial.pending";
        else if (end != null) key = "trial.ended";
        else key = switch (account.status()) {
            case "active" -> "active";
            case "past_due", "unpaid" -> "payment.due";
            case "canceled", "incomplete_expired" -> "canceled";
            case "paused" -> "paused";
            default -> "pending";
        };
        return messages.getMessage("workspace.billing.status." + key, null, account.displayStatus(), locale);
    }

    @PostMapping("/owner/workspace/{tenant}/setup")
    public String setup(@PathVariable String tenant,@RequestParam String action,
            @RequestParam(required=false) String template,
            @RequestParam(required=false) String theme,
            Principal owner, RedirectAttributes flash, Locale locale) {
        checkOpen(owner,tenant);
        if("adopt".equals(action) && template != null && !template.isBlank()) {
            if(link.linkedProduction()) {
                // Concepts are for practising: a production linked to its sandbox gets its menu by promotion
                flash.addFlashAttribute("error", messages.getMessage("workspace.adopt.concepts.sandbox", null, "Concepts are loaded in the sandbox. Open it with Practise in the sandbox.", locale));
                return "redirect:/owner/console?location=" + tenant;
            }
            if ("empty".equalsIgnoreCase(template.strip())) {
                workspace.action(owner.getName(), tenant, "start");
                workspace.ensureSampleTable(owner.getName(), tenant);
                flash.addFlashAttribute("notice", messages.getMessage("workspace.adopt.empty.ready", null, "Your workspace is ready with an empty menu. Add your first dishes to get started.", locale));
                return "redirect:/owner/console?location=" + tenant;
            }
            try {
                backups.importDemoTemplate(tenant, template.strip());
                String appliedTheme = theme;
                if (appliedTheme == null || appliedTheme.isBlank()) {
                    appliedTheme = switch (template.toLowerCase().strip()) {
                        case "kebab" -> "fastfood";
                        case "cafe" -> "cafe";
                        case "michelin" -> "modern";
                        default -> "fastfood";
                    };
                }
                if(appliedTheme != null && !appliedTheme.isBlank()) {
                    appearance.saveLocationTheme(tenant, appliedTheme);
                }
                workspace.action(owner.getName(), tenant, "start");
                flash.addFlashAttribute("notice", messages.getMessage("workspace.adopt.concept.ready", null, "Your restaurant menu and visual style are ready! Explore your kitchen, counter and tables below.", locale));
            } catch(Exception e) {
                log.error("Could not load concept {} for tenant {}", template, tenant, e);
                flash.addFlashAttribute("error", messages.getMessage("workspace.adopt.concept.failed", null, "Could not load that sample menu. Try again or start with an empty menu.", locale));
            }
            return "redirect:/owner/console?location=" + tenant;
        }
        workspace.action(owner.getName(),tenant,action);
        return "redirect:/owner/console?location="+tenant;
    }
    @PostMapping("/owner/workspace/{tenant}/cash-float")
    public String cashFloat(@PathVariable String tenant,@RequestParam(defaultValue="") String cashFloat,Principal owner,RedirectAttributes flash) {
        checkOpen(owner,tenant);
        try {
            workspace.saveCashFloat(owner.getName(),tenant,new BigDecimal(cashFloat.strip().replace(',','.')));
            flash.addFlashAttribute("notice",workspaceMessage("workspace.flash.cash.saved"));
        } catch(NumberFormatException e) {
            flash.addFlashAttribute("error",workspaceMessage("workspace.flash.cash.invalid"));
        } catch(IllegalArgumentException e) {
            flash.addFlashAttribute("error",workspaceMessage("workspace.flash.cash.invalid"));
        }
        return "redirect:/owner/console?view=location-settings&location="+tenant;
    }
    @PostMapping("/owner/workspace/{tenant}/service")
    public String service(@PathVariable String tenant,@RequestParam(defaultValue="false") boolean dineIn,@RequestParam(defaultValue="false") boolean takeaway,
            @RequestParam(defaultValue="0") int yellow,@RequestParam(defaultValue="0") int red,@RequestParam(defaultValue="false") boolean tableQrTrusted,Principal owner,RedirectAttributes flash) {
        checkOpen(owner,tenant);
        try { workspace.saveService(owner.getName(),tenant,dineIn,takeaway,yellow,red,tableQrTrusted);flash.addFlashAttribute("notice",workspaceMessage("workspace.flash.service.saved")); }
        catch(IllegalArgumentException e) { flash.addFlashAttribute("error",workspaceMessage("workspace.flash.service.invalid")); }
        return "redirect:/owner/console?view=location-settings&location="+tenant;
    }
    /** A draft the owner just tried to save wins over the saved profile, so nothing typed is lost. */
    private void addLegal(Model model, FiscalProfile saved, String tenant, String owner) {
        model.addAttribute("savedFiscalTaxId", saved == null ? null : saved.taxId());
        FiscalProfile fiscal = model.containsAttribute("fiscalDraft") ? (FiscalProfile) model.getAttribute("fiscalDraft") : saved;
        model.addAttribute("fiscal", fiscal);
        model.addAttribute("provinces", SpanishProvinces.NAMES);
        model.addAttribute("vatRates", VatRates.ALL.stream().sorted(Comparator.comparing((BigDecimal rate) -> rate.compareTo(VatRates.DEFAULT) != 0)).toList());
        model.addAttribute("suggestedPrefix", FiscalProfile.suggestPrefix(fiscal.displayName() != null ? fiscal.displayName() : tenant));
        model.addAttribute("ticketYear", String.format("%02d", Year.now().getValue() % 100));
        if (owner != null && fiscal != null && fiscal.taxId() != null) {
            workspace.priorNifUsage(owner, fiscal.taxId(), tenant).ifPresent(seenAt -> {
                String formatted = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")
                        .withZone(java.time.ZoneId.of("Europe/Madrid")).format(seenAt);
                model.addAttribute("priorNifSeenAt", formatted);
            });
        }
    }

    @PostMapping("/owner/workspace/{tenant}/legal")
    public String legal(@PathVariable String tenant, @RequestParam Map<String, String> form, Principal owner,
            RedirectAttributes flash, Locale locale) {
        checkOpen(owner, tenant);
        BigDecimal rate;
        try { rate = new BigDecimal(form.getOrDefault("defaultVatRate", "")); }
        catch (NumberFormatException e) { rate = VatRates.DEFAULT; }
        FiscalProfile profile = new FiscalProfile(form.get("legalName"), form.get("tradeName"), form.get("taxId"),
                form.get("addressLine"), form.get("postalCode"), form.get("city"), form.get("province"),
                form.get("phone"), form.get("email"), form.get("seriesPrefix"), rate);
        FiscalProfile previous = workspace.fiscalProfile(owner.getName(), tenant);
        Map<String, String> wrong = workspace.saveFiscalProfile(owner.getName(), tenant, profile);
        if (!wrong.isEmpty()) {
            flash.addFlashAttribute("fiscalDraft", profile);
            flash.addFlashAttribute("fiscalErrors", new java.util.HashMap<>(wrong));
        } else {
            Optional<String> newNif = SpanishTaxId.parse(profile.taxId()).map(SpanishTaxId::value);
            Optional<String> oldNif = SpanishTaxId.parse(previous == null ? null : previous.taxId()).map(SpanishTaxId::value);
            String warning = null;
            if (!environment.sandbox() && newNif.isPresent() && !newNif.equals(oldNif)) {
                if (verifactuAuthorizations == null || !verifactuAuthorizations.hasValidAuthorization(newNif.get())) {
                    warning = messages.getMessage("owner.legal.nif.authorization_needed", null,
                            "Business details saved. The VERI*FACTU authorisation for this NIF is not valid yet. Check or complete signing before issuing tickets.", locale);
                } else if (!verifactuAuthorizations.isCollaborationActive()) {
                    warning = messages.getMessage("owner.legal.nif.collaboration_inactive", null,
                            "Business details saved. This NIF has a valid authorisation, but ticket issuing is on hold until AEAT collaboration is activated.", locale);
                }
            }
            if (warning != null) flash.addFlashAttribute("error", warning);
            else flash.addFlashAttribute("notice", messages.getMessage(
                    profile.complete() ? "owner.legal.saved" : "owner.legal.saved.incomplete", null, locale));
        }
        return "redirect:/owner/console?view=location-settings&section=legal&location=" + tenant
                + (!wrong.isEmpty() ? "&fiscalError=1" : "");
    }

    /**
     * Every billing record of the location as JSON, in chain order: the restaurant can keep it and
     * show it to an inspector, and anyone can recompute the fingerprints to check the chain.
     */
    @GetMapping("/owner/workspace/{tenant}/verifactu/records.json")
    public org.springframework.http.ResponseEntity<List<es.brasatech.fastbite.domain.verifactu.VerifactuRecord>> verifactuRecords(
            @PathVariable String tenant, Principal owner) {
        check(owner, tenant);
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"verifactu-" + tenant + ".json\"")
                .body(workspace.verifactuRecords(owner.getName(), tenant, environment.sandbox()));
    }

    /**
     * Every issued ticket and invoice of the location as JSON, oldest first, so the owner can
     * consult and download all fiscal invoices even after a location's contract has ended.
     */
    @GetMapping("/owner/workspace/{tenant}/tickets.json")
    public org.springframework.http.ResponseEntity<List<es.brasatech.fastbite.domain.fiscal.FiscalTicket>> fiscalTicketsJson(
            @PathVariable String tenant, Principal owner) {
        check(owner, tenant);
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"tickets-" + tenant + ".json\"")
                .body(workspace.fiscalTickets(owner.getName(), tenant));
    }

    /** Printable view of an issued ticket for the location's owner, including on a closed location. */
    @GetMapping("/owner/workspace/{tenant}/tickets/{ticketId}")
    public String fiscalTicketPrint(@PathVariable String tenant, @PathVariable String ticketId, Principal owner, Model model) {
        check(owner, tenant);
        var ticket = workspace.fiscalTicket(owner.getName(), tenant, ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("ticket", ticket);
        workspace.ticketCancellation(owner.getName(), tenant, ticket.id())
                .ifPresent(cancellation -> model.addAttribute("cancellation", cancellation));
        workspace.verifactuRecordForTicket(owner.getName(), tenant, ticket.id()).ifPresent(record -> {
            model.addAttribute("verifactu", record);
            model.addAttribute("qrSvg", es.brasatech.fastbite.service.QrSvg.of(record.qrUrl(), "QR tributario"));
        });
        return "fastfood/ticket";
    }

    /** Sends a refused record (or one accepted with errors) again, as a subsanación. */
    @PostMapping("/owner/workspace/{tenant}/verifactu/records/{record}/amend")
    public String amendVerifactuRecord(@PathVariable String tenant, @PathVariable String record, Principal owner,
            RedirectAttributes flash, Locale locale) {
        checkOpen(owner, tenant);
        try {
            workspace.amendVerifactuRecord(owner.getName(), tenant, record);
            flash.addFlashAttribute("notice", messages.getMessage("owner.verifactu.amended", null, locale));
        } catch (IllegalStateException | IllegalArgumentException e) {
            flash.addFlashAttribute("error", messages.getMessage("owner.verifactu.amend.failed", null, locale));
        }
        return "redirect:/owner/console?view=location-settings&section=legal&location=" + tenant;
    }

    @PostMapping("/owner/workspace/{tenant}/backup-preview") @ResponseBody
    public TenantBackupRestorePort.BackupPreview preview(@PathVariable String tenant,@RequestParam MultipartFile file,Principal owner)throws java.io.IOException {
        checkOpen(owner,tenant);
        try(var stream=file.getInputStream()) { return backups.previewBackup(tenant,stream); }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage()); }
    }

    public record PromotionErrorResponse(String code, String message) {}
    public record ConfirmPromotionRequest(String token) {}

    @es.brasatech.fastbite.config.ProductionOnly
    @PostMapping("/owner/workspace/{tenant}/promote/preview")
    @ResponseBody
    public ResponseEntity<?> previewPromotion(@PathVariable String tenant, Principal owner, Locale locale) {
        checkOpen(owner, tenant);
        try {
            return ResponseEntity.ok(promotionService.preview(tenant, owner.getName()));
        } catch (es.brasatech.fastbite.application.platform.PromotionException e) {
            String msg = messages.getMessage(e.code(), null, workspaceMessage("workspace.flash.failed"), locale);
            return ResponseEntity.status(e.statusCode()).body(new PromotionErrorResponse(e.code(), msg));
        } catch (Exception e) {
            log.error("Promotion preview failed unexpectedly for tenant {}", tenant, e);
            String msg = messages.getMessage("promotion.failed", null, "Promotion failed", locale);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new PromotionErrorResponse("promotion.failed", msg));
        }
    }

    @es.brasatech.fastbite.config.ProductionOnly
    @PostMapping("/owner/workspace/{tenant}/promote/confirm")
    @ResponseBody
    public ResponseEntity<?> confirmPromotion(
            @PathVariable String tenant,
            @RequestBody(required = false) ConfirmPromotionRequest body,
            Principal owner,
            Locale locale) {
        checkOpen(owner, tenant);
        String actualToken = body != null ? body.token() : null;
        if (actualToken == null || actualToken.isBlank()) {
            String msg = messages.getMessage("promotion.token.invalid", null, "Invalid promotion token", locale);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new PromotionErrorResponse("promotion.token.invalid", msg));
        }
        try {
            var result = promotionService.confirm(tenant, owner.getName(), actualToken);
            return ResponseEntity.ok(Map.of("status", "applied", "snapshot", result.snapshotName(), "summary", result.summary()));
        } catch (es.brasatech.fastbite.application.platform.PromotionException e) {
            String msg = messages.getMessage(e.code(), null, workspaceMessage("workspace.flash.failed"), locale);
            return ResponseEntity.status(e.statusCode()).body(new PromotionErrorResponse(e.code(), msg));
        } catch (Exception e) {
            log.error("Promotion confirm failed unexpectedly for tenant {}", tenant, e);
            String msg = messages.getMessage("promotion.failed", null, "Promotion failed", locale);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new PromotionErrorResponse("promotion.failed", msg));
        }
    }

    @es.brasatech.fastbite.config.ProductionOnly
    @PostMapping("/owner/workspace/{tenant}/promote/undo")
    @ResponseBody
    public ResponseEntity<?> undoPromotion(@PathVariable String tenant, Principal owner, Locale locale) {
        checkOpen(owner, tenant);
        try {
            var result = promotionService.undo(tenant, owner.getName());
            return ResponseEntity.ok(Map.of("status", "undone", "snapshot", result.snapshotName()));
        } catch (es.brasatech.fastbite.application.platform.PromotionException e) {
            String msg = messages.getMessage(e.code(), null, workspaceMessage("workspace.flash.failed"), locale);
            return ResponseEntity.status(e.statusCode()).body(new PromotionErrorResponse(e.code(), msg));
        } catch (Exception e) {
            log.error("Promotion undo failed unexpectedly for tenant {}", tenant, e);
            String msg = messages.getMessage("promotion.failed", null, "Promotion failed", locale);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new PromotionErrorResponse("promotion.failed", msg));
        }
    }
    @es.brasatech.fastbite.config.ProductionOnly
    @PostMapping("/owner/marketing-consent")
    public String updateMarketingConsent(@RequestParam(defaultValue="false") boolean marketing,
            Principal owner, RedirectAttributes flash) {
        if (owner == null) return "redirect:/login";
        if (marketing) {
            acceptances.record(owner.getName(), LegalAcceptancePort.MARKETING, termsVersion, clock.instant());
            flash.addFlashAttribute("notice", workspaceMessage("workspace.flash.marketing.on"));
        } else {
            acceptances.withdraw(owner.getName(), LegalAcceptancePort.MARKETING, clock.instant());
            flash.addFlashAttribute("notice", workspaceMessage("workspace.flash.marketing.off"));
        }
        return "redirect:/owner/console?view=settings";
    }

    @PostMapping("/owner/workspace/{tenant}/privacy-notice")
    public String uploadPrivacyNotice(@PathVariable String tenant, @RequestParam("file") MultipartFile file,
            Principal owner, RedirectAttributes flash) {
        checkOpen(owner, tenant);
        try {
            byte[] bytes = file != null ? file.getBytes() : new byte[0];
            String originalName = file != null ? file.getOriginalFilename() : "privacy.pdf";
            var saved = privacyNotices.upload(tenant, originalName, bytes, owner.getName());
            flash.addFlashAttribute("notice", workspaceMessage("workspace.flash.privacy.uploaded", saved.version(), tenant));
        } catch (IllegalArgumentException | java.io.IOException e) {
            flash.addFlashAttribute("error", e instanceof LocalizedRejection ? RejectionMessages.resolve(e, messages) : workspaceMessage("workspace.flash.failed"));
        }
        return "redirect:/owner/console?view=location-settings&section=legal&location=" + tenant;
    }

    @PostMapping("/owner/workspace/{tenant}/privacy-notice/generated")
    public String useGeneratedPrivacyNotice(@PathVariable String tenant, Principal owner, RedirectAttributes flash) {
        checkOpen(owner, tenant);
        privacyNotices.useGeneratedNotice(tenant);
        flash.addFlashAttribute("notice", workspaceMessage("workspace.flash.privacy.restored"));
        return "redirect:/owner/console?view=location-settings&section=legal&location=" + tenant;
    }

    /**
     * Starts or resumes Digital Sign VERI*FACTU Anexo I signing for this location's NIF.
     * Redirects directly to the signing URL (opened in a new tab by the form) without logging or storing it.
     */
    @PostMapping("/owner/workspace/{tenant}/verifactu/authorize")
    public String startVerifactuAuthorization(@PathVariable String tenant, Principal owner,
            RedirectAttributes flash, Locale locale) {
        checkOpen(owner, tenant);
        if (verifactuAuthorizations == null) {
            flash.addFlashAttribute("error", workspaceMessage("opening.verifactu.reason.signing_unavailable"));
            return "redirect:/owner/console?location=" + tenant;
        }
        FiscalProfile fiscal = workspace.fiscalProfile(owner.getName(), tenant);
        Optional<OwnerWorkspacePort.OwnerSigner> signerOpt = workspace.ownerSigner(owner.getName());
        String ownerName = signerOpt.map(OwnerWorkspacePort.OwnerSigner::fullName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(owner.getName());
        String ownerEmail = signerOpt.map(OwnerWorkspacePort.OwnerSigner::verifiedEmail)
                .filter(email -> email != null && !email.isBlank())
                .orElse(null);
        if (ownerEmail == null && environment.sandbox()) {
            ownerEmail = fiscal != null && fiscal.email() != null && !fiscal.email().isBlank()
                    ? fiscal.email().strip()
                    : "sandbox@brasatech.es";
        }
        String lang = locale != null ? locale.getLanguage() : "es";
        StartSigningResult result = verifactuAuthorizations.startSigning(
                fiscal, owner.getName(), ownerName, ownerEmail, lang);
        return switch (result.outcome()) {
            case SIGNING_URL -> "redirect:" + result.signingUrl();
            case ALREADY_VALID -> {
                flash.addFlashAttribute("notice", workspaceMessage("opening.verifactu.already.valid"));
                yield "redirect:/owner/console?location=" + tenant;
            }
            case FISCAL_INCOMPLETE -> {
                flash.addFlashAttribute("error", workspaceMessage("opening.verifactu.status.fiscal_required"));
                yield "redirect:/owner/console?location=" + tenant;
            }
            case SIGNING_UNAVAILABLE -> {
                String msgKey = switch (result.reason() == null ? "" : result.reason()) {
                    case "DOWNLOAD_FAILED" -> "opening.verifactu.reason.download_failed";
                    case "TEST_ENVIRONMENT" -> "opening.verifactu.reason.test_environment";
                    case "LIVE_ENVIRONMENT" -> "opening.verifactu.reason.live_environment";
                    default -> "opening.verifactu.reason.signing_unavailable";
                };
                flash.addFlashAttribute("error", workspaceMessage(msgKey));
                yield "redirect:/owner/console?location=" + tenant;
            }
        };
    }

    /** User-driven status refresh ("Comprobar ahora") for an active VERI*FACTU authorisation. */
    @PostMapping("/owner/workspace/{tenant}/verifactu/refresh")
    public String refreshVerifactuAuthorization(@PathVariable String tenant, Principal owner,
            RedirectAttributes flash) {
        checkOpen(owner, tenant);
        if (verifactuAuthorizations != null) {
            FiscalProfile fiscal = workspace.fiscalProfile(owner.getName(), tenant);
            if (fiscal != null && fiscal.taxId() != null && !fiscal.taxId().isBlank()) {
                verifactuAuthorizations.refreshForNif(fiscal.taxId());
                workspace.summary(owner.getName(), tenant);
            }
        }
        flash.addFlashAttribute("notice", workspaceMessage("opening.verifactu.refreshed"));
        return "redirect:/owner/console?location=" + tenant;
    }

    /** Revokes the currently VALID VERI*FACTU authorisation for this location's NIF. */
    @PostMapping("/owner/workspace/{tenant}/verifactu/revoke")
    public String revokeVerifactuAuthorization(
            @PathVariable String tenant,
            @RequestParam(name = "confirmNif", required = false) String confirmNif,
            Principal owner,
            RedirectAttributes flash) {
        checkOpen(owner, tenant);
        if (verifactuAuthorizations == null) {
            flash.addFlashAttribute("error", workspaceMessage("opening.verifactu.reason.signing_unavailable"));
            return "redirect:/owner/console?location=" + tenant;
        }
        FiscalProfile fiscal = workspace.fiscalProfile(owner.getName(), tenant);
        String normalizedNif = normalizeNif(fiscal == null ? null : fiscal.taxId());
        if (normalizedNif == null) {
            flash.addFlashAttribute("error", workspaceMessage("opening.verifactu.status.fiscal_required"));
            return "redirect:/owner/console?location=" + tenant;
        }
        if (!environment.sandbox()) {
            String normalizedConfirm = normalizeNif(confirmNif);
            if (normalizedConfirm == null || !normalizedNif.equals(normalizedConfirm)) {
                flash.addFlashAttribute("error", workspaceMessage("opening.verifactu.revoke.error.nif_mismatch"));
                return "redirect:/owner/console?location=" + tenant;
            }
        }
        RevokeResult result = verifactuAuthorizations.revoke(normalizedNif, owner.getName());
        if (result.outcome() == RevokeResult.Outcome.REVOKED) {
            flash.addFlashAttribute("notice", workspaceMessage("opening.verifactu.revoke.success"));
        } else {
            flash.addFlashAttribute("error", workspaceMessage("opening.verifactu.revoke.error.not_valid"));
        }
        return "redirect:/owner/console?location=" + tenant;
    }

    /**
     * Permanently deletes a sandbox location, or a production location that has never issued a
     * ticket or recorded a paid order. Refuses deletion if the production location has tickets.
     */
    @PostMapping("/owner/workspace/{tenant}/delete")
    public String deleteLocation(
            @PathVariable String tenant,
            @RequestParam(name = "confirmName", required = false) String confirmName,
            Principal owner,
            RedirectAttributes flash) {
        checkOpen(owner, tenant);
        if (locationLifecycle == null) {
            flash.addFlashAttribute("error", workspaceMessage("workspace.flash.failed"));
            return "redirect:/owner/console?view=location-settings&section=general&location=" + tenant;
        }
        try {
            LocationLifecycleService.DeleteResult result = locationLifecycle.deleteLocation(owner.getName(), tenant, confirmName);
            return switch (result.outcome()) {
                case DELETED -> {
                    evictTenantRoutes(tenant, result.customDomain());
                    flash.addFlashAttribute("notice", workspaceMessage("workspace.flash.location.deleted", tenant));
                    yield "redirect:/owner/console?view=locations";
                }
                case NAME_MISMATCH -> {
                    flash.addFlashAttribute("error", workspaceMessage("workspace.flash.location.confirm.mismatch"));
                    yield "redirect:/owner/console?view=location-settings&section=general&location=" + tenant;
                }
                case HAS_TICKETS -> {
                    flash.addFlashAttribute("error", workspaceMessage("workspace.flash.location.delete.has_tickets"));
                    yield "redirect:/owner/console?view=location-settings&section=general&location=" + tenant;
                }
            };
        } catch (IllegalStateException e) {
            log.warn("Could not delete location {}: {}", tenant, e.getMessage());
            flash.addFlashAttribute("error", workspaceMessage("workspace.flash.location.billing.unavailable"));
            return "redirect:/owner/console?view=location-settings&section=general&location=" + tenant;
        }
    }

    /**
     * Permanently closes a production location that has issued tickets, stopping service,
     * scheduling Stripe cancellation at period end, and retaining fiscal &amp; VERI*FACTU records.
     */
    @PostMapping("/owner/workspace/{tenant}/close")
    public String closeLocation(
            @PathVariable String tenant,
            @RequestParam(name = "confirmName", required = false) String confirmName,
            Principal owner,
            RedirectAttributes flash) {
        checkOpen(owner, tenant);
        if (locationLifecycle == null) {
            flash.addFlashAttribute("error", workspaceMessage("workspace.flash.failed"));
            return "redirect:/owner/console?view=location-settings&section=general&location=" + tenant;
        }
        try {
            LocationLifecycleService.CloseResult result = locationLifecycle.closeLocation(owner.getName(), tenant, confirmName);
            return switch (result.outcome()) {
                case CLOSED -> {
                    evictTenantRoutes(tenant, result.customDomain());
                    flash.addFlashAttribute("notice", workspaceMessage("workspace.flash.location.closed", tenant));
                    yield "redirect:/owner/console?view=locations";
                }
                case NAME_MISMATCH -> {
                    flash.addFlashAttribute("error", workspaceMessage("workspace.flash.location.confirm.mismatch"));
                    yield "redirect:/owner/console?view=location-settings&section=general&location=" + tenant;
                }
                case SANDBOX_USE_DELETE -> {
                    flash.addFlashAttribute("error", workspaceMessage("workspace.flash.location.close.sandbox"));
                    yield "redirect:/owner/console?view=location-settings&section=general&location=" + tenant;
                }
            };
        } catch (IllegalStateException e) {
            log.warn("Could not close location {}: {}", tenant, e.getMessage());
            flash.addFlashAttribute("error", workspaceMessage("workspace.flash.location.billing.unavailable"));
            return "redirect:/owner/console?view=location-settings&section=general&location=" + tenant;
        }
    }

    private void evictTenantRoutes(String tenant, String customDomain) {
        if (routingResolver == null) {
            return;
        }
        routingResolver.evictTenant(tenant);
        if (customDomain != null && !customDomain.isBlank()) {
            routingResolver.evictCache(customDomain);
        }
    }

    private VerifactuStepView buildVerifactuStep(String ownerUsername, String selectedTenantId, FiscalProfile fiscal) {
        if (fiscal == null || !fiscal.complete()) {
            return new VerifactuStepView(
                    false,
                    fiscal != null ? fiscal.taxId() : null,
                    fiscal != null ? fiscal.legalName() : null,
                    "FISCAL_REQUIRED",
                    "opening.verifactu.status.fiscal_required",
                    null,
                    null,
                    false,
                    false,
                    false,
                    false,
                    List.of());
        }
        String nif = fiscal.taxId();
        String legalName = fiscal.legalName();
        if (verifactuAuthorizations == null) {
            return new VerifactuStepView(
                    true, nif, legalName, "SIGNING_UNAVAILABLE",
                    "opening.verifactu.status.on_hold", "opening.verifactu.reason.signing_unavailable",
                    null, false, false, false, false, List.of());
        }
        boolean signingConfigured = verifactuAuthorizations.isSigningConfigured();
        Optional<VerifactuAuthorization> currentOpt = verifactuAuthorizations.findCurrentForNif(nif);
        if (currentOpt.isEmpty()) {
            if (!signingConfigured) {
                return new VerifactuStepView(
                        true, nif, legalName, "SIGNING_UNAVAILABLE",
                        "opening.verifactu.status.on_hold", "opening.verifactu.reason.signing_unavailable",
                        null, false, false, false, false, List.of());
            }
            return new VerifactuStepView(
                    true, nif, legalName, "NOT_SIGNED",
                    "opening.verifactu.status.not_signed", null,
                    null, true, false, false, false, List.of());
        }
        VerifactuAuthorization auth = currentOpt.get();
        if (auth.isCurrentlyValid()) {
            List<String> affected = locationsSharingNif(ownerUsername, selectedTenantId, nif);
            if (environment.sandbox() || verifactuAuthorizations.isCollaborationActive()) {
                return new VerifactuStepView(
                        true, nif, legalName, "VALID",
                        "opening.verifactu.status.valid", null,
                        auth.signerName(), false, false, false, true, affected);
            }
            return new VerifactuStepView(
                    true, nif, legalName, "COLLABORATION_INACTIVE",
                    "opening.verifactu.status.signed_waiting_switch", "opening.verifactu.reason.collaboration_inactive",
                    auth.signerName(), false, false, false, true, affected);
        }
        if (auth.status() == VerifactuAuthorizationStatus.IN_PROGRESS) {
            return new VerifactuStepView(
                    true, nif, legalName, "IN_PROGRESS",
                    "opening.verifactu.status.in_progress", null,
                    auth.signerName(), signingConfigured, true, false, false, List.of());
        }
        if (auth.status() == VerifactuAuthorizationStatus.DOWNLOAD_FAILED) {
            return new VerifactuStepView(
                    true, nif, legalName, "ON_HOLD",
                    "opening.verifactu.status.on_hold", "opening.verifactu.reason.download_failed",
                    auth.signerName(), false, true, false, false, List.of());
        }
        String reasonKey = switch (auth.status()) {
            case DECLINED -> "opening.verifactu.reason.declined";
            case EXPIRED -> "opening.verifactu.reason.expired";
            case CANCELLED -> "opening.verifactu.reason.cancelled";
            case NIF_MISMATCH -> "opening.verifactu.reason.nif_mismatch";
            case NIF_UNVERIFIED -> "opening.verifactu.reason.nif_unverified";
            case TEST_ENVIRONMENT -> "opening.verifactu.reason.test_environment";
            case LIVE_ENVIRONMENT -> "opening.verifactu.reason.live_environment";
            case DOCUMENTS_GONE -> {
                if ("REQUEST_NOT_FOUND".equals(auth.failureReason())) {
                    yield "opening.verifactu.reason.request_not_found";
                }
                if ("DOCUMENTS_NOT_READY".equals(auth.failureReason())) {
                    yield "opening.verifactu.reason.documents_not_ready";
                }
                yield "opening.verifactu.reason.documents_gone";
            }
            case REVOKED -> "opening.verifactu.reason.revoked";
            default -> "opening.verifactu.reason.signing_unavailable";
        };
        boolean retryable = signingConfigured;
        return new VerifactuStepView(
                true, nif, legalName, "ON_HOLD",
                "opening.verifactu.status.on_hold", reasonKey,
                auth.signerName(), false, false, retryable, false, List.of());
    }

    private List<String> locationsSharingNif(String ownerUsername, String selectedTenantId, String nif) {
        String normalized = normalizeNif(nif);
        if (normalized == null || ownerUsername == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (TenantLocation loc : locations.getLocationsByOwner(ownerUsername)) {
            if (loc.isClosed()) {
                continue;
            }
            if (loc.tenantId().equals(selectedTenantId)) {
                result.add(loc.tenantId());
                continue;
            }
            FiscalProfile other = workspace.fiscalProfile(ownerUsername, loc.tenantId());
            if (other != null && normalized.equals(normalizeNif(other.taxId()))) {
                result.add(loc.tenantId());
            }
        }
        if (result.isEmpty() && selectedTenantId != null) {
            result.add(selectedTenantId);
        }
        return List.copyOf(result);
    }

    private static String normalizeNif(String nif) {
        if (nif == null || nif.isBlank()) {
            return null;
        }
        return SpanishTaxId.parse(nif)
                .map(SpanishTaxId::value)
                .orElseGet(() -> nif.strip().replaceAll("[\\s-]+", "").toUpperCase(Locale.ROOT));
    }

    public record VerifactuStepView(
            boolean fiscalComplete,
            String nif,
            String legalName,
            String state,
            String statusMessageKey,
            String reasonMessageKey,
            String signerName,
            boolean canSign,
            boolean canRefresh,
            boolean canRetry,
            boolean canRevoke,
            List<String> affectedLocations) {
    }

    private void check(Principal owner,String tenant) {
        try { workspace.requireOwner(owner==null?null:owner.getName(),tenant); }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
    }

    private void checkOpen(Principal owner,String tenant) {
        check(owner,tenant);
        if(subscriptions.isClosed(tenant) || locations.isClosed(tenant)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This location is closed.");
        }
    }
}
