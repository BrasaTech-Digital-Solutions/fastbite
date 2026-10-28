package es.brasatech.fastbite.dto.office;

import es.brasatech.fastbite.application.office.CustomizationService;
import es.brasatech.fastbite.application.office.GroupService;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.domain.customization.CustomizationDto;
import es.brasatech.fastbite.domain.customization.CustomizationOptionDto;
import es.brasatech.fastbite.domain.group.Group;
import es.brasatech.fastbite.domain.product.ProductDto;
import es.brasatech.fastbite.dto.menu.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service to convert BackOffice data into MenuData format for the customer menu.
 */
@Service
@RequiredArgsConstructor
public class MenuDataService {

    private final GroupService groupService;
    private final CustomizationService customizationService;
    private final ProductService productService;
    private final MessageSource messageSource;

    /**
     * Build complete MenuData from BackOffice entities.
     * This method converts Groups, Customizations, and Products into the format
     * expected by the customer-facing menu.
     *
     * @param locale the locale for internationalization
     * @return MenuData ready for the menu page
     */
    public MenuData buildMenuData(Locale locale) {
        // Get all entities from BackOffice
        List<Group> groups = groupService.findAll();
        List<CustomizationDto> customizationDtos = customizationService.findAll().stream()
                .filter(c -> c.options() != null && !c.options().isEmpty())
                .toList();
        List<ProductDto> productDtos = productService.findAll();

        Set<String> validCustomizationIds = customizationDtos.stream()
                .map(CustomizationDto::id)
                .collect(Collectors.toSet());

        // Convert to menu entities
        List<Tab> tabs = convertGroupsToTabs(groups);
        List<Customization> customizations = convertCustomizations(customizationDtos);
        Map<String, Product> productsById = convertProducts(productDtos, validCustomizationIds);

        // Group products by tab
        Map<String, List<Product>> menuDataMap = groupProductsByTab(groups, productsById);

        // Build comma-separated IDs
        String availableCustomizations = customizations.stream()
                .map(Customization::id)
                .distinct()
                .collect(Collectors.joining(","));

        String availableTabsId = tabs.stream()
                .map(Tab::id)
                .collect(Collectors.joining(","));

        // Build dictionary
        Map<String, String> dictionary = buildDictionary(locale);

        return new MenuData(
                menuDataMap,
                customizations,
                availableCustomizations,
                dictionary,
                tabs,
                availableTabsId
        );
    }

    /**
     * Convert BackOffice Groups to menu Tabs
     */
    private List<Tab> convertGroupsToTabs(List<Group> groups) {
        return groups.stream()
                .map(group -> new Tab(group.id(), group.name()))
                .toList();
    }

    /**
     * Convert BackOffice CustomizationDto to menu Customization
     */
    private List<Customization> convertCustomizations(List<CustomizationDto> customizationDtos) {
        return customizationDtos.stream()
                .map(customDto -> {
                    // Convert type string to enum
                    CustomizationInputType inputType = convertInputType(customDto.type());

                    // Convert options
                    List<CustomizationOption> options = convertCustomizationOptions(customDto.options());

                    return new Customization(
                            customDto.id(),
                            customDto.name(),
                            inputType,
                            options
                    );
                })
                .toList();
    }

    /**
     * Convert BackOffice type string to CustomizationInputType enum
     */
    private CustomizationInputType convertInputType(String type) {
        return switch (type.toLowerCase()) {
            case "radio" -> CustomizationInputType.RADIO;
            case "checkbox" -> CustomizationInputType.CHECKBOX;
            case "quantity" -> CustomizationInputType.HIDDEN;
            default -> CustomizationInputType.RADIO; // Default fallback
        };
    }

    /**
     * Convert BackOffice CustomizationOptionDto to menu CustomizationOption
     */
    private List<CustomizationOption> convertCustomizationOptions(List<CustomizationOptionDto> optionDtos) {
        List<CustomizationOption> options = new ArrayList<>();

        for (CustomizationOptionDto optionDto : optionDtos) {
            // Use the values from the DTO
            options.add(new CustomizationOption(
                    optionDto.id(),
                    optionDto.name(),
                    optionDto.price(),
                    optionDto.isSelectedByDefault(),
                    optionDto.defaultValue(),
                    optionDto.allergens() != null ? optionDto.allergens() : java.util.Set.of()
            ));
        }

        return options;
    }

    /**
     * Convert BackOffice ProductDto to menu Product
     */
    private Map<String, Product> convertProducts(List<ProductDto> productDtos, Set<String> validCustomizationIds) {
        return productDtos.stream()
                .collect(Collectors.toMap(
                        ProductDto::id,
                        productDto -> {
                            // Convert customizations list to array, keeping only non-empty customizations
                            String[] customizations = productDto.customizations() != null
                                    ? productDto.customizations().stream()
                                            .filter(validCustomizationIds::contains)
                                            .toArray(String[]::new)
                                    : new String[0];

                            return new Product(
                                    productDto.id(),
                                    productDto.name(),
                                    productDto.price(),
                                    productDto.description(),
                                    productDto.image(),
                                    customizations,
                                    productDto.active(),
                                    productDto.allergens()
                            );
                        }
                ));
    }

    /**
     * Group products by their assigned Group (Tab)
     */
    private Map<String, List<Product>> groupProductsByTab(List<Group> groups, Map<String, Product> productsById) {
        Map<String, List<Product>> menuDataMap = new HashMap<>();

        for (Group group : groups) {
            List<Product> productsInGroup = group.products().stream()
                    .map(productsById::get)
                    .filter(Objects::nonNull)  // Filter out null products
                    .distinct()
                    .toList();

            menuDataMap.put(group.id(), productsInGroup);
        }

        return menuDataMap;
    }

    /**
     * Build internationalized dictionary for the menu
     */
    private Map<String, String> buildDictionary(Locale locale) {
        return Map.of(
                "cartEmpty", messageSource.getMessage("message.your.cart.is.empty", null, locale),
                "confirmAndSubmitBtn", messageSource.getMessage("button.confirm.submit.order", null, locale),
                "total", messageSource.getMessage("label.total", null, locale),
                "subtotal", messageSource.getMessage("label.subtotal", null, locale),
                "tax", messageSource.getMessage("label.tax", null, locale),
                "orderConfirmation", messageSource.getMessage("label.order.confirmation", null, locale),
                "orderTotal", messageSource.getMessage("label.order.total", null, locale),
                "orderItems", messageSource.getMessage("label.order.items", null, locale)
        );
    }
}
