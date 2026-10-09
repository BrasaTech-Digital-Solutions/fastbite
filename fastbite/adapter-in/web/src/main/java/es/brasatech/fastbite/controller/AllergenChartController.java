package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.office.CustomizationService;
import es.brasatech.fastbite.application.office.GroupService;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.domain.allergen.Allergen;
import es.brasatech.fastbite.domain.customization.CustomizationOptionDto;
import es.brasatech.fastbite.domain.product.ProductDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The allergen chart (carta de alérgenos): every dish on sale against the 14 allergens, for
 * guests to read on their phone and for the restaurant to print and keep at hand.
 */
@Controller
@RequiredArgsConstructor
public class AllergenChartController {

    private final ProductService productService;
    private final GroupService groupService;
    private final CustomizationService customizationService;
    private final FiscalProfileService fiscalProfileService;

    /** A section of the chart: a menu category and its dishes. */
    public record Section(String name, List<ProductDto> products) {
    }

    /** An option that adds allergens to the dishes it is offered with. */
    public record OptionRow(String customization, CustomizationOptionDto option) {
    }

    @GetMapping({"/{tenantId}/allergens", "/allergens"})
    public String chart(@PathVariable(required = false) String tenantId, Model model) {
        Map<String, ProductDto> onSale = new LinkedHashMap<>();
        productService.findAll().stream().filter(ProductDto::active).forEach(product -> onSale.put(product.id(), product));

        List<Section> sections = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        groupService.findAll().forEach(group -> {
            List<ProductDto> products = group.products() == null ? List.of() : group.products().stream()
                    .map(onSale::get).filter(product -> product != null && placed.add(product.id())).toList();
            if (!products.isEmpty()) {
                sections.add(new Section(group.name(), products));
            }
        });
        List<ProductDto> uncategorised = onSale.values().stream().filter(product -> !placed.contains(product.id())).toList();
        if (!uncategorised.isEmpty()) {
            sections.add(new Section(null, uncategorised));
        }

        List<OptionRow> options = new ArrayList<>();
        customizationService.findAll().forEach(customization -> customization.options().stream()
                .filter(option -> option.allergens() != null && !option.allergens().isEmpty())
                .forEach(option -> options.add(new OptionRow(customization.name(), option))));

        var profile = fiscalProfileService.get();
        model.addAttribute("sections", sections);
        model.addAttribute("options", options);
        model.addAttribute("allergens", Allergen.values());
        model.addAttribute("restaurantName", profile.displayName() != null ? profile.displayName() : tenantId);
        model.addAttribute("undeclared", onSale.values().stream().filter(product -> !product.allergensDeclared()).count());
        model.addAttribute("today", LocalDate.now());
        return "fastfood/allergens";
    }
}
