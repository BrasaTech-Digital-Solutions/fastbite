package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.settings.RestaurantSettingsService;
import es.brasatech.fastbite.application.tenant.PlanEntitlementService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
import es.brasatech.fastbite.domain.tenant.PlanFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequiredArgsConstructor
public class BackOfficeController {

    private final PlanEntitlementService planEntitlementService;
    private final RestaurantSettingsService settingsService;
    private final TenantLocationService tenantLocationService;
    private final es.brasatech.fastbite.application.fiscal.FiscalProfileService fiscalProfileService;
    private final org.springframework.context.MessageSource messageSource;
    private final es.brasatech.fastbite.application.tenant.FreePlanLock freePlanLock;

    /**
     * Render the BackOffice page
     */
    @GetMapping({"/{tenantId}/backoffice", "/backoffice"})
    public String backOffice(@PathVariable(required = false) String tenantId, Model model) {
        String activeTenant = (tenantId != null) ? tenantId : "kebab";
        String plan = tenantLocationService.getLocation(activeTenant)
                .map(loc -> loc.plan())
                .orElse("Basic Plan");

        Set<PlanFeature> enabledFeatures = planEntitlementService.getEnabledFeatures(plan);

        model.addAttribute("activeTenant", activeTenant);
        model.addAttribute("tenantPlan", plan);
        model.addAttribute("freePlanLocked", freePlanLock.locked(activeTenant));
        model.addAttribute("enabledFeatures", enabledFeatures);
        model.addAttribute("settings", settingsService.get());
        model.addAttribute("defaultVatRate", fiscalProfileService.defaultVatRate());
        model.addAttribute("vatRates", es.brasatech.fastbite.domain.fiscal.VatRates.ALL);
        model.addAttribute("allergenList", List.of(es.brasatech.fastbite.domain.allergen.Allergen.values()));
        return "fastfood/backOffice";
    }

    @GetMapping({"/{tenantId}/api/backoffice/settings", "/api/backoffice/settings"})
    @ResponseBody
    public RestaurantSettings getSettings() {
        return settingsService.get();
    }

    @PutMapping({"/{tenantId}/api/backoffice/settings", "/api/backoffice/settings"})
    @ResponseBody
    public RestaurantSettings saveSettings(@RequestBody RestaurantSettings settings) {
        return settingsService.save(settings);
    }

    @PostMapping("/api/backoffice/fragments/groups-list")
    public String getGroupsList(@RequestBody List<Map<String, Object>> groups, Model model) {
        model.addAttribute("groups", new java.util.ArrayList<>(groups));
        return "fastfood/fragments/backOffice :: groups-list";
    }

    @PostMapping("/api/backoffice/fragments/customizations-list")
    public String getCustomizationsList(@RequestBody List<Map<String, Object>> customizations, Model model) {
        model.addAttribute("customizations", new java.util.ArrayList<>(customizations));
        return "fastfood/fragments/backOffice :: customizations-list";
    }

    @PostMapping("/api/backoffice/fragments/products-list")
    public String getProductsList(@RequestBody List<Map<String, Object>> products, Model model, java.util.Locale locale) {
        // Templates cannot reach the allergen enum, so each product gets its icons and names here
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Map<String, Object> product : products) {
            Map<String, Object> row = new java.util.HashMap<>(product);
            if (product.get("allergens") instanceof java.util.Collection<?> codes) {
                var allergens = es.brasatech.fastbite.domain.allergen.Allergen.parse(
                        codes.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
                row.put("allergenIcons", allergens.stream().map(es.brasatech.fastbite.domain.allergen.Allergen::icon)
                        .collect(java.util.stream.Collectors.joining()));
                row.put("allergenNames", allergens.stream()
                        .map(allergen -> messageSource.getMessage(allergen.messageKey(), null, locale))
                        .collect(java.util.stream.Collectors.joining(", ")));
            }
            rows.add(row);
        }
        model.addAttribute("products", rows);
        return "fastfood/fragments/backOffice :: products-list";
    }

    @PostMapping("/api/backoffice/fragments/customization-option")
    public String getCustomizationOption(@RequestBody Map<String, Object> payload, Model model) {
        model.addAttribute("opt", payload.get("opt"));
        model.addAttribute("index", payload.get("index"));
        model.addAttribute("allergenList", List.of(es.brasatech.fastbite.domain.allergen.Allergen.values()));
        return "fastfood/fragments/backOffice :: customization-option";
    }

    /** A folder of the image gallery and its images, as the page sent them. */
    public record ImageFolder(String name, List<?> images) {
    }

    @PostMapping("/api/backoffice/fragments/image-gallery")
    public String getImageGallery(@RequestBody Map<String, Object> payload, Model model) {
        // A list of records rather than the map: map entries are JDK classes the native image cannot read
        List<ImageFolder> folders = new java.util.ArrayList<>();
        if (payload.get("imagesByFolder") instanceof Map<?, ?> byFolder) {
            byFolder.forEach((name, images) -> folders.add(new ImageFolder(String.valueOf(name),
                    images instanceof List<?> list ? list : List.of())));
        }
        model.addAttribute("folders", folders);
        model.addAttribute("type", payload.get("type"));
        return "fastfood/fragments/backOffice :: image-gallery";
    }

    @PostMapping("/api/backoffice/fragments/quick-view")
    public String getQuickView(@RequestBody Map<String, Object> payload, Model model) {
        model.addAttribute("data", payload.get("data"));
        model.addAttribute("type", payload.get("type"));
        return "fastfood/fragments/backOffice :: quick-view";
    }

    @PostMapping("/api/backoffice/fragments/group-products-badges")
    public String getGroupProductsBadges(@RequestBody List<Map<String, Object>> selectedProducts, Model model) {
        model.addAttribute("selectedProducts", selectedProducts);
        return "fastfood/fragments/backOffice :: group-products-badges";
    }

    @PostMapping("/api/backoffice/fragments/product-customizations-badges")
    public String getProductCustomizationsBadges(@RequestBody List<Map<String, Object>> selectedCustomizations,
            Model model) {
        model.addAttribute("selectedCustomizations", selectedCustomizations);
        return "fastfood/fragments/backOffice :: product-customizations-badges";
    }

    @PostMapping("/api/backoffice/fragments/selector-list")
    public String getSelectorList(@RequestBody Map<String, Object> payload, Model model) {
        model.addAttribute("items", payload.get("items"));
        model.addAttribute("selectedIds", payload.get("selectedIds"));
        return "fastfood/fragments/backOffice :: selector-list";
    }

    @PostMapping("/api/backoffice/fragments/tables-list")
    public String getTablesList(@RequestBody List<Map<String, Object>> tables, Model model) {
        model.addAttribute("tables", new java.util.ArrayList<>(tables));
        return "fastfood/fragments/backOffice :: tables-list";
    }

    @PostMapping("/api/backoffice/fragments/table-form")
    public String getTableForm(@RequestBody Map<String, Object> payload, Model model) {
        model.addAttribute("table", payload.get("table"));
        model.addAttribute("statuses", payload.get("statuses"));
        return "fastfood/fragments/backOffice :: table-form";
    }

    @PostMapping("/api/backoffice/fragments/payment-section")
    public String getPaymentSection(@RequestBody Map<String, Object> payload, Model model) {
        model.addAttribute("paymentConfig", payload.get("paymentConfig"));
        model.addAttribute("allConfigs", payload.get("allConfigs"));
        return "fastfood/fragments/backOffice :: payment-section";
    }

    @PostMapping("/api/backoffice/fragments/discounts-list")
    public String getDiscountsList(@RequestBody List<Map<String, Object>> discounts, Model model) {
        model.addAttribute("discounts", new java.util.ArrayList<>(discounts));
        return "fastfood/fragments/backOffice :: discounts-list";
    }

    @PostMapping("/api/backoffice/fragments/discount-form")
    public String getDiscountForm(@RequestBody Map<String, Object> payload, Model model) {
        model.addAttribute("discount", payload.get("discount"));
        return "fastfood/fragments/backOffice :: discount-form";
    }

}
