package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.domain.tenant.SubscriptionPlan;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.security.SessionCookie;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * FastBite's legal pages, and each restaurant's privacy notice for its guests. The texts are drafts
 * awaiting review by a lawyer (see docs/legal/README.md); Spanish is the binding version, English and
 * Portuguese are translations. The company's details come from the configuration, in one place.
 */
@Controller
public class LegalController {

    /** The pages, in the order the navigation lists them. */
    static final List<String> PAGES = List.of("notice", "privacy", "cookies", "terms", "data-processing");
    static final Set<String> LANGUAGES = Set.of("es", "en", "pt");

    /** Each page's title by language, for the tab, the navigation and the language links. */
    static final Map<String, Map<String, String>> TITLES = Map.of(
            "es", Map.of("notice", "Aviso legal", "privacy", "Política de privacidad", "cookies", "Política de cookies",
                    "terms", "Condiciones del servicio", "data-processing", "Contrato de encargo del tratamiento",
                    "guests", "Información de privacidad para clientes"),
            "en", Map.of("notice", "Legal notice", "privacy", "Privacy policy", "cookies", "Cookie policy",
                    "terms", "Terms of service", "data-processing", "Data processing agreement",
                    "guests", "Privacy notice for guests"),
            "pt", Map.of("notice", "Aviso legal", "privacy", "Política de privacidade", "cookies", "Política de cookies",
                    "terms", "Termos de serviço", "data-processing", "Contrato de subcontratação de tratamento de dados",
                    "guests", "Aviso de privacidade para clientes"));

    /**
     * Brasatech as the provider of FastBite.
     *
     * @param registry the Mercantile Registry entry; blank until it is configured
     * @param updated  when the texts were last changed, as shown on every page
     */
    public record Company(String name, String taxId, String address, String email, String registry, String website,
            String updated) {
    }

    /** A link to one of the legal pages, titled in the page's language. */
    public record NavLink(String page, String title, boolean current) {
    }

    /** The plans as the terms describe them. */
    public record Plans(String monthlyPrice, int trialDays, String freeFee) {
    }

    private final FiscalProfileService restaurants;
    private final SessionCookie sessionCookie;
    private final Company company;
    private final Plans plans;

    public LegalController(FiscalProfileService restaurants, SessionCookie sessionCookie,
            @Value("${fastbite.legal.company-name:BRASATECH DIGITAL SOLUTIONS S.L.}") String name,
            @Value("${fastbite.legal.tax-id:B09767898}") String taxId,
            @Value("${fastbite.legal.address:Calle Ganaderos, 24, 03130 Santa Pola (Alicante), España}") String address,
            @Value("${fastbite.legal.email:info@brasatech.es}") String email,
            @Value("${fastbite.legal.registry:}") String registry,
            @Value("${fastbite.legal.website:https://fastbite.brasatech.es}") String website,
            @Value("${fastbite.legal.updated:16/10/2026}") String updated) {
        this.restaurants = restaurants;
        this.sessionCookie = sessionCookie;
        this.company = new Company(name, taxId, address, email, registry, website, updated);
        this.plans = new Plans(euros(BigDecimal.valueOf(SubscriptionPlan.MONTHLY_CENTS, 2)), SubscriptionPlan.TRIAL_DAYS,
                euros(BigDecimal.valueOf(SubscriptionPlan.FREE_PLAN_APPLICATION_FEE_BPS, 2)));
    }

    @GetMapping("/legal/{page}")
    public String page(@PathVariable String page, @RequestParam(required = false) String lang, Locale locale, Model model) {
        if (!PAGES.contains(page)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return show(page, lang, locale, model);
    }

    /** What a restaurant does with its guests' data: the restaurant decides, FastBite processes it for them. */
    @GetMapping({"/{tenantId}/privacy", "/privacy"})
    public String guests(@PathVariable(required = false) String tenantId, @RequestParam(required = false) String lang,
            Locale locale, Model model) {
        // /legal/privacy matches this pattern too, and Spring prefers it: it is FastBite's own policy
        if ("legal".equals(tenantId)) {
            return page("privacy", lang, locale, model);
        }
        // Only for a restaurant: by its path, or by its own address (/privacy on its host)
        if (TenantContext.getCurrentTenant() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        model.addAttribute("restaurant", restaurants.get());
        model.addAttribute("guestsPath", (tenantId != null ? "/" + tenantId : "") + "/privacy");
        return show("guests", lang, locale, model);
    }

    private String show(String page, String lang, Locale locale, Model model) {
        String language = lang != null && LANGUAGES.contains(lang) ? lang
                : LANGUAGES.contains(locale.getLanguage()) ? locale.getLanguage() : "es";
        model.addAttribute("page", page);
        model.addAttribute("language", language);
        Map<String, String> titles = TITLES.get(language);
        model.addAttribute("title", titles.get(page));
        model.addAttribute("navigation", PAGES.stream().map(p -> new NavLink(p, titles.get(p), p.equals(page))).toList());
        model.addAttribute("prefix", "guests".equals(page) ? model.getAttribute("guestsPath") : "/legal/" + page);
        model.addAttribute("company", company);
        model.addAttribute("plans", plans);
        // The cookie policy names the cookie as configured, so it stays true behind any proxy
        model.addAttribute("sessionCookie", sessionCookie.name());
        return "fastfood/legal/page";
    }

    /** 49,00 written as Spanish texts write money: 49 or 2,5. */
    private static String euros(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString().replace('.', ',');
    }
}
