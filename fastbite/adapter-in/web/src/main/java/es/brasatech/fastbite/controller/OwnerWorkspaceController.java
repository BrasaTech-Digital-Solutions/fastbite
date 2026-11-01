package es.brasatech.fastbite.controller;
import es.brasatech.fastbite.application.tenant.*;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.fiscal.SpanishProvinces;
import es.brasatech.fastbite.domain.fiscal.VatRates;
import es.brasatech.fastbite.domain.user.Role;
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
    public OwnerWorkspaceController(TenantLocationService locations,OwnerWorkspaceService workspace,OwnerStaffService staff,SubscriptionService subscriptions,TenantBackupRestorePort backups,AppearancePort appearance,es.brasatech.fastbite.config.ThemeCatalog themes,
            MessageSource messages, es.brasatech.fastbite.application.platform.PlatformEnvironment environment,
            es.brasatech.fastbite.application.platform.PlatformLink link, FreePlanLock freePlanLock,
            es.brasatech.fastbite.application.platform.PlatformAdmins admins,
            LegalAcceptancePort acceptances,
            es.brasatech.fastbite.application.settings.GuestPrivacyNoticeService privacyNotices,
            java.time.Clock clock,
            @org.springframework.beans.factory.annotation.Value("${fastbite.legal.updated:27/10/2026}") String termsVersion,
            es.brasatech.fastbite.application.platform.PromotionService promotionService,
            @org.springframework.beans.factory.annotation.Value("${fastbite.orders.time-zone:Europe/Madrid}") String ordersTimeZone) {
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
    }
    @GetMapping("/owner/console")
    public String show(Principal owner,Model model,@RequestParam(defaultValue="overview") String view,
            @RequestParam(required=false) String location,@RequestParam(defaultValue="general") String section,
            Locale locale) {
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
                var setup=workspace.summary(owner.getName(),selected.tenantId());
                model.addAttribute("setup",setup);
                if (view.equals("overview") && setup.opened() && !environment.sandbox()) {
                    model.addAttribute("todayVerifactu", workspace.verifactuSummary(owner.getName(), selected.tenantId(), false));
                }
                if(view.equals("location-settings") && section.equals("general")) {
                    model.addAttribute("cashFloat",workspace.cashFloat(owner.getName(),selected.tenantId()));
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
        Map<String, String> wrong = workspace.saveFiscalProfile(owner.getName(), tenant, profile);
        if (!wrong.isEmpty()) {
            flash.addFlashAttribute("fiscalDraft", profile);
            flash.addFlashAttribute("fiscalErrors", new java.util.HashMap<>(wrong));
        } else {
            flash.addFlashAttribute("notice", messages.getMessage(
                    profile.complete() ? "owner.legal.saved" : "owner.legal.saved.incomplete", null, locale));
        }
        return "redirect:/owner/console?view=location-settings&section=legal&location=" + tenant;
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
