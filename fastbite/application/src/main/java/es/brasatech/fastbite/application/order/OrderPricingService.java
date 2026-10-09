package es.brasatech.fastbite.application.order;

import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.office.CustomizationService;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.domain.customization.CustomizationDto;
import es.brasatech.fastbite.domain.customization.CustomizationOptionDto;
import es.brasatech.fastbite.domain.order.CartItem;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.product.ProductCustomizer;
import es.brasatech.fastbite.domain.product.ProductDto;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Prices order lines from the catalog. A request only decides which product, how many and
 * which options; the names, images, prices and VAT rates sent along with it are ignored.
 */
@Service
public class OrderPricingService {

    private static final int MAX_QUANTITY = 99;

    private final ProductService productService;
    private final CustomizationService customizationService;
    private final FiscalProfileService fiscalProfileService;

    public OrderPricingService(ProductService productService, CustomizationService customizationService,
            FiscalProfileService fiscalProfileService) {
        this.productService = productService;
        this.customizationService = customizationService;
        this.fiscalProfileService = fiscalProfileService;
    }

    /**
     * The requested lines with name, description, image and prices taken from the catalog.
     * Guests can only order products that are on sale; the counter can sell any product.
     *
     * @throws IllegalArgumentException if a line asks for a product or option it cannot have
     */
    public List<CartItem> price(List<CartItem> requested, OrderChannel channel) {
        if (requested == null) {
            return new ArrayList<>();
        }
        Map<String, Optional<ProductDto>> products = new HashMap<>();
        Map<String, Optional<CustomizationDto>> customizations = new HashMap<>();
        BigDecimal defaultVatRate = requested.isEmpty() ? null : fiscalProfileService.defaultVatRate();
        // ArrayList: carts are kept in the session and read by templates, like the lists Jackson builds
        return requested.stream()
                .map(line -> price(line, channel, products, customizations, defaultVatRate))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private CartItem price(CartItem line, OrderChannel channel, Map<String, Optional<ProductDto>> products,
                           Map<String, Optional<CustomizationDto>> customizations, BigDecimal defaultVatRate) {
        if (line.itemId() == null) {
            throw new IllegalArgumentException("An order line has no product");
        }
        ProductDto product = products.computeIfAbsent(line.itemId(), productService::findById)
                .orElseThrow(() -> new IllegalArgumentException("A product in this order is no longer on the menu"));
        if (channel != OrderChannel.COUNTER && !product.active()) {
            throw new IllegalArgumentException(product.name() + " is sold out");
        }
        if (line.quantity() < 1 || line.quantity() > MAX_QUANTITY) {
            throw new IllegalArgumentException("Choose between 1 and " + MAX_QUANTITY + " of " + product.name());
        }
        // Options are part of the dish, so they are charged at the dish's rate
        return new CartItem(line.id(), product.id(), product.name(), product.description(), product.image(),
                line.quantity(), options(line, product, customizations), product.price(),
                product.vatRate() != null ? product.vatRate() : defaultVatRate);
    }

    private List<ProductCustomizer> options(CartItem line, ProductDto product,
                                            Map<String, Optional<CustomizationDto>> customizations) {
        if (line.customizations() == null || line.customizations().isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, CustomizationOptionDto> offered = new HashMap<>();
        Map<String, CustomizationDto> customizationOf = new HashMap<>();
        if (product.customizations() != null) {
            for (String customizationId : product.customizations()) {
                customizations.computeIfAbsent(customizationId, customizationService::findById).ifPresent(customization ->
                        customization.options().forEach(option -> {
                            offered.put(option.id(), option);
                            customizationOf.put(option.id(), customization);
                        }));
            }
        }
        Set<String> chosen = new HashSet<>();
        return line.customizations().stream().map(requested -> {
            CustomizationOptionDto option = offered.get(requested.id());
            if (option == null) {
                throw new IllegalArgumentException("This option is not available for " + product.name());
            }
            CustomizationDto customization = customizationOf.get(option.id());
            boolean oneChoiceOnly = "radio".equalsIgnoreCase(customization.type());
            if (!chosen.add(option.id()) || (oneChoiceOnly && !chosen.add(customization.id()))) {
                throw new IllegalArgumentException("Choose one " + customization.name() + " for " + product.name());
            }
            return new ProductCustomizer(option.id(), option.name(), option.price(), Math.max(0, requested.quantity()));
        }).collect(Collectors.toCollection(ArrayList::new));
    }
}
