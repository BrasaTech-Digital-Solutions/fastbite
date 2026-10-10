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
    public OwnerWorkspaceController(TenantLocationService locations,OwnerWorkspaceService workspace,OwnerStaffService staff,SubscriptionService subscriptions,TenantBackupRestorePort backups,AppearancePort appearance,es.brasatech.fastbite.config.ThemeCatalog themes,
            MessageSource messages) {
        this.locations=locations;this.workspace=workspace;this.staff=staff;this.subscriptions=subscriptions;this.backups=backups;
        this.appearance=appearance;this.themes=themes;this.messages=messages;
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
            if(section.equals("legal")) {
                addLegal(model,setup.fiscal(),selected.tenantId());
                model.addAttribute("fiscalLocked",workspace.fiscalLocked(owner.getName(),selected.tenantId()));
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
