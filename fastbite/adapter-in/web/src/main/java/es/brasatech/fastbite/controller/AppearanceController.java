package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.tenant.AppearancePort;
import es.brasatech.fastbite.application.tenant.OwnerWorkspaceService;
import es.brasatech.fastbite.config.ThemeCatalog;
import org.springframework.core.io.Resource;
import org.springframework.util.DigestUtils;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.security.Principal;

@Controller
public class AppearanceController {
    private final AppearancePort preferences;
    private final ThemeCatalog themes;
    private final OwnerWorkspaceService workspace;
    public AppearanceController(AppearancePort preferences,ThemeCatalog themes,OwnerWorkspaceService workspace) {
        this.preferences=preferences;this.themes=themes;this.workspace=workspace;
    }
    @GetMapping("/images/themes/{theme}/{file}") @ResponseBody
    public ResponseEntity<Resource> asset(@PathVariable String theme,@PathVariable String file) throws IOException {
        Resource resource=themes.asset(theme,file);
        if(resource==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        boolean versioned=file.equals("theme-"+themes.resolve(theme).cssHash()+".css");
        CacheControl cache=versioned ? CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()
                : CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.endsWith(".css")?"text/css":"image/svg+xml"))
                .cacheControl(cache).eTag("W/\""+DigestUtils.md5DigestAsHex(resource.getContentAsByteArray())+"\"").body(resource);
    }
    @PostMapping("/owner/appearance")
    public String owner(@RequestParam String theme,Principal owner,RedirectAttributes flash) {
        try { themes.require(theme);preferences.saveOwnerTheme(owner.getName(),theme);flash.addFlashAttribute("notice","Default theme saved. Locations using your default now share this appearance."); }
        catch(IllegalArgumentException e) { flash.addFlashAttribute("error",e.getMessage()); }
        return "redirect:/owner/console?view=settings";
    }
    @PostMapping("/owner/workspace/{tenant}/appearance")
    public String location(@PathVariable String tenant,@RequestParam String theme,Principal owner,RedirectAttributes flash) {
        try { workspace.requireOwner(owner.getName(),tenant); }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
        try {
            if(!theme.equals("inherit")) themes.require(theme);
            preferences.saveLocationTheme(tenant,theme.equals("inherit")?null:theme);
            flash.addFlashAttribute("notice","Location appearance saved.");
        } catch(IllegalArgumentException e) { flash.addFlashAttribute("error",e.getMessage()); }
        return "redirect:/owner/console?view=location-settings&section=appearance&location="+tenant;
    }
}
