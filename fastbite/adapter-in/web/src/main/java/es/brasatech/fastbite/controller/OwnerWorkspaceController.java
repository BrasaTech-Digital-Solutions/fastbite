package es.brasatech.fastbite.controller;
import es.brasatech.fastbite.application.tenant.*;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.fiscal.SpanishProvinces;
import es.brasatech.fastbite.domain.fiscal.VatRates;
import es.brasatech.fastbite.domain.user.Role;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Year;
import java.util.*;
@Controller
public class OwnerWorkspaceController {
    private final TenantLocationService locations;
    private final OwnerWorkspaceService workspace;
    private final OwnerStaffService staff;
    private final SubscriptionService subscriptions;
    private final TenantBackupRestorePort backups;
    private final AppearancePort appearance;
    private final es.brasatech.fastbite.config.ThemeCatalog themes;
    private final MessageSource messages;
    private final es.brasatech.fastbite.application.platform.PlatformEnvironment environment;
    private final es.brasatech.fastbite.application.platform.PlatformLink link;
    public OwnerWorkspaceController(TenantLocationService locations,OwnerWorkspaceService workspace,OwnerStaffService staff,SubscriptionService subscriptions,TenantBackupRestorePort backups,AppearancePort appearance,es.brasatech.fastbite.config.ThemeCatalog themes,
            MessageSource messages, es.brasatech.fastbite.application.platform.PlatformEnvironment environment,
            es.brasatech.fastbite.application.platform.PlatformLink link) {
        this.locations=locations;this.workspace=workspace;this.staff=staff;this.subscriptions=subscriptions;this.backups=backups;
        this.appearance=appearance;this.themes=themes;this.messages=messages;this.environment=environment;this.link=link;
    }
    @GetMapping("/owner/console")
    public String show(Principal owner,Model model,@RequestParam(defaultValue="overview") String view,
            @RequestParam(required=false) String location,@RequestParam(defaultValue="general") String section,
            Locale locale) {
        if(owner==null) return "redirect:/login";
        if(!Set.of("overview","locations","team","location-settings","settings").contains(view)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(!Set.of("general","legal","appearance","payments","domain","backup","advanced").contains(section)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var list=locations.getLocationsByOwner(owner.getName()).stream().sorted(Comparator.comparing(es.brasatech.fastbite.domain.tenant.TenantLocation::tenantId)).toList();
        var selected=location==null?(list.isEmpty()?null:list.getFirst()):list.stream().filter(l->l.tenantId().equals(location)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("ownerUsername",owner.getName());model.addAttribute("locations",list);
        model.addAttribute("selected",selected);model.addAttribute("view",view);model.addAttribute("section",section);
        model.addAttribute("themes",themes.all());
        model.addAttribute("ownerTheme",themes.resolve(appearance.ownerTheme(owner.getName())));
        model.addAttribute("demoTemplates",backups.getAvailableTemplates(locale!=null?locale:Locale.getDefault()));
        if(selected!=null) {
            model.addAttribute("selectedTheme",themes.resolve(appearance.effectiveTheme(selected.tenantId())));
            model.addAttribute("themeOverride",appearance.locationTheme(selected.tenantId()));
            var setup=workspace.summary(owner.getName(),selected.tenantId());
            model.addAttribute("setup",setup);
            if(view.equals("location-settings") && section.equals("general")) {
                model.addAttribute("cashFloat",workspace.cashFloat(owner.getName(),selected.tenantId()));
            }
            if(section.equals("legal")) {
                addLegal(model,setup.fiscal(),selected.tenantId());
                model.addAttribute("fiscalLocked",workspace.fiscalLocked(owner.getName(),selected.tenantId()));
                model.addAttribute("verifactu",workspace.verifactuSummary(owner.getName(),selected.tenantId(),environment.sandbox()));
            }
            model.addAttribute("billing",subscriptions.account(selected.tenantId(),owner.getName()));
            if(view.equals("team")) {
                model.addAttribute("users",staff.listStaff(owner.getName(),selected.tenantId()).stream().filter(u->!u.isOwner()).toList());
                model.addAttribute("tenantId",selected.tenantId());
                model.addAttribute("roles",Arrays.stream(Role.values()).filter(r->r!=Role.OWNER).toList());
            }
        }
        return "fastfood/owner/console";
    }
    @PostMapping("/owner/workspace/{tenant}/setup")
    public String setup(@PathVariable String tenant,@RequestParam String action,
            @RequestParam(required=false) String template,
            @RequestParam(required=false) String theme,
            Principal owner, RedirectAttributes flash) {
        check(owner,tenant);
        if("adopt".equals(action) && template != null && !template.isBlank()) {
            if(link.linkedProduction()) {
                // Concepts are for practising: a production linked to its sandbox gets its menu by promotion
                flash.addFlashAttribute("error", "Concepts are loaded in the sandbox. Open it with Practise in the sandbox.");
                return "redirect:/owner/console?location=" + tenant;
            }
            try {
                backups.importDemoTemplate(tenant, template);
                if(theme != null && !theme.isBlank()) {
                    appearance.saveLocationTheme(tenant, theme);
                }
                workspace.action(owner.getName(), tenant, "start");
                flash.addFlashAttribute("notice", "Your restaurant menu and visual style are ready! Explore your kitchen, counter and tables below.");
            } catch(Exception e) {
                flash.addFlashAttribute("error", "Could not load selected concept: " + e.getMessage());
            }
            return "redirect:/owner/console?location=" + tenant;
        }
        workspace.action(owner.getName(),tenant,action);
        return action.equals("start")?"redirect:/"+tenant+"/backoffice":"redirect:/owner/console?location="+tenant;
    }
    @PostMapping("/owner/workspace/{tenant}/cash-float")
    public String cashFloat(@PathVariable String tenant,@RequestParam(defaultValue="") String cashFloat,Principal owner,RedirectAttributes flash) {
        check(owner,tenant);
        try {
            workspace.saveCashFloat(owner.getName(),tenant,new BigDecimal(cashFloat.strip().replace(',','.')));
            flash.addFlashAttribute("notice","Starting cash saved. The counter offers it when the cash is checked.");
        } catch(NumberFormatException e) {
            flash.addFlashAttribute("error","Enter the starting cash in euros, such as 150 or 150.50.");
        } catch(IllegalArgumentException e) {
            flash.addFlashAttribute("error",e.getMessage());
        }
        return "redirect:/owner/console?view=location-settings&location="+tenant;
    }
    @PostMapping("/owner/workspace/{tenant}/service")
    public String service(@PathVariable String tenant,@RequestParam(defaultValue="false") boolean dineIn,@RequestParam(defaultValue="false") boolean takeaway,
            @RequestParam(defaultValue="0") int yellow,@RequestParam(defaultValue="0") int red,Principal owner,RedirectAttributes flash) {
        check(owner,tenant);
        try { workspace.saveService(owner.getName(),tenant,dineIn,takeaway,yellow,red);flash.addFlashAttribute("notice","Service preferences saved. Your setup progress has been updated."); }
        catch(IllegalArgumentException e) { flash.addFlashAttribute("error",e.getMessage()); }
        return "redirect:/owner/console?view=location-settings&location="+tenant;
    }
    /** A draft the owner just tried to save wins over the saved profile, so nothing typed is lost. */
    private void addLegal(Model model, FiscalProfile saved, String tenant) {
        FiscalProfile fiscal = model.containsAttribute("fiscalDraft") ? (FiscalProfile) model.getAttribute("fiscalDraft") : saved;
        model.addAttribute("fiscal", fiscal);
        model.addAttribute("provinces", SpanishProvinces.NAMES);
        model.addAttribute("vatRates", VatRates.ALL.stream().sorted(Comparator.comparing((BigDecimal rate) -> rate.compareTo(VatRates.DEFAULT) != 0)).toList());
        model.addAttribute("suggestedPrefix", FiscalProfile.suggestPrefix(fiscal.displayName() != null ? fiscal.displayName() : tenant));
        model.addAttribute("ticketYear", String.format("%02d", Year.now().getValue() % 100));
    }

    @PostMapping("/owner/workspace/{tenant}/legal")
    public String legal(@PathVariable String tenant, @RequestParam Map<String, String> form, Principal owner,
            RedirectAttributes flash, Locale locale) {
        check(owner, tenant);
        BigDecimal rate;
        try { rate = new BigDecimal(form.getOrDefault("defaultVatRate", "")); }
        catch (NumberFormatException e) { rate = VatRates.DEFAULT; }
        FiscalProfile profile = new FiscalProfile(form.get("legalName"), form.get("tradeName"), form.get("taxId"),
                form.get("addressLine"), form.get("postalCode"), form.get("city"), form.get("province"),
                form.get("phone"), form.get("email"), form.get("seriesPrefix"), rate);
        Map<String, String> wrong = workspace.saveFiscalProfile(owner.getName(), tenant, profile);
        if (!wrong.isEmpty()) {
            flash.addFlashAttribute("fiscalDraft", profile);
            flash.addFlashAttribute("fiscalErrors", wrong);
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

    /** Sends a refused record (or one accepted with errors) again, as a subsanación. */
    @PostMapping("/owner/workspace/{tenant}/verifactu/records/{record}/amend")
    public String amendVerifactuRecord(@PathVariable String tenant, @PathVariable String record, Principal owner,
            RedirectAttributes flash, Locale locale) {
        check(owner, tenant);
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
        check(owner,tenant);
        try(var stream=file.getInputStream()) { return backups.previewBackup(tenant,stream); }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage()); }
    }
    private void check(Principal owner,String tenant) {
        try { workspace.requireOwner(owner==null?null:owner.getName(),tenant); }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
    }
}
