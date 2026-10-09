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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderPricingServiceTest {

    private final ProductService products = mock(ProductService.class);
    private final CustomizationService customizations = mock(CustomizationService.class);
    private final FiscalProfileService fiscalProfile = mock(FiscalProfileService.class);
    private final OrderPricingService pricing = new OrderPricingService(products, customizations, fiscalProfile);

    @BeforeEach
    void catalog() {
        when(fiscalProfile.defaultVatRate()).thenReturn(new BigDecimal("10.00"));
        when(products.findById("beer")).thenReturn(Optional.of(new ProductDto("beer", "Beer to go", new BigDecimal("2.50"),
                "Can of beer", "/beer.webp", Set.of(), true, new BigDecimal("21.00"))));
        when(products.findById("kebab")).thenReturn(Optional.of(new ProductDto("kebab", "Kebab", new BigDecimal("6.50"),
                "Beef kebab", "/kebab.webp", Set.of("sauce", "toppings"), true)));
        when(products.findById("soldout")).thenReturn(Optional.of(new ProductDto("soldout", "Falafel", new BigDecimal("5.00"),
                "Falafel wrap", "/falafel.webp", Set.of(), false)));
        when(products.findById("water")).thenReturn(Optional.of(new ProductDto("water", "Water", new BigDecimal("1.50"),
                "Still water", "/water.webp", Set.of(), true)));
        when(customizations.findById("sauce")).thenReturn(Optional.of(new CustomizationDto("sauce", "Sauce", "radio", List.of(
                new CustomizationOptionDto("sauce-opt-0", "Garlic", BigDecimal.ZERO, true, 1),
                new CustomizationOptionDto("sauce-opt-1", "Spicy", BigDecimal.ZERO, false, 0)), 0)));
        when(customizations.findById("toppings")).thenReturn(Optional.of(new CustomizationDto("toppings", "Toppings", "checkbox", List.of(
                new CustomizationOptionDto("toppings-opt-0", "Extra cheese", new BigDecimal("0.50"), false, 0),
                new CustomizationOptionDto("toppings-opt-1", "Extra bacon", new BigDecimal("0.80"), false, 0)), 0)));
    }

    private static CartItem line(String productId, int quantity, String... optionIds) {
        List<ProductCustomizer> options = List.of(optionIds).stream()
                .map(id -> new ProductCustomizer(id, "Free gold", new BigDecimal("-100"), 1))
                .toList();
        return new CartItem("line-1", productId, "Hacked", "<script>", "/evil.png", quantity, options, new BigDecimal("0.01"));
    }

    @Test
    void eachLineIsChargedAtItsProductsVatRateOrTheRestaurantRate() {
        List<CartItem> priced = pricing.price(List.of(line("kebab", 1), line("beer", 1)), OrderChannel.COUNTER);

        assertThat(priced.get(0).vatRate()).isEqualByComparingTo("10");
        assertThat(priced.get(1).vatRate()).isEqualByComparingTo("21");
    }

    @Test
    void theVatRateSentWithARequestIsIgnored() {
        var line = line("kebab", 1).withVatRate(BigDecimal.ZERO);

        assertThat(pricing.price(List.of(line), OrderChannel.TABLE).getFirst().vatRate()).isEqualByComparingTo("10");
    }

    @Test
    void pricesNamesAndImagesComeFromTheCatalogNotTheRequest() {
        List<CartItem> priced = pricing.price(List.of(line("kebab", 2, "sauce-opt-1", "toppings-opt-0")), OrderChannel.TABLE);

        assertThat(priced).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("line-1");
            assertThat(item.name()).isEqualTo("Kebab");
            assertThat(item.description()).isEqualTo("Beef kebab");
            assertThat(item.image()).isEqualTo("/kebab.webp");
            assertThat(item.price()).isEqualByComparingTo("6.50");
            assertThat(item.customizations()).extracting(ProductCustomizer::name).containsExactly("Spicy", "Extra cheese");
            assertThat(item.totalPrice()).isEqualByComparingTo("14.00"); // (6.50 + 0.50) x 2
        });
    }

    @Test
    void optionsOfAnotherProductAreRejected() {
        assertThatThrownBy(() -> pricing.price(List.of(line("water", 1, "toppings-opt-0")), OrderChannel.TABLE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not available for Water");
    }

    @Test
    void onlyOneChoicePerSingleChoiceOptionAndNoOptionTwice() {
        assertThatThrownBy(() -> pricing.price(List.of(line("kebab", 1, "sauce-opt-0", "sauce-opt-1")), OrderChannel.TABLE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Choose one Sauce");
        assertThatThrownBy(() -> pricing.price(List.of(line("kebab", 1, "toppings-opt-0", "toppings-opt-0")), OrderChannel.TABLE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(pricing.price(List.of(line("kebab", 1, "toppings-opt-0", "toppings-opt-1")), OrderChannel.TABLE))
                .singleElement().satisfies(item -> assertThat(item.totalPrice()).isEqualByComparingTo("7.80"));
    }

    @Test
    void quantitiesMustBeSensible() {
        assertThatThrownBy(() -> pricing.price(List.of(line("kebab", -3)), OrderChannel.TABLE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pricing.price(List.of(line("kebab", 0)), OrderChannel.TABLE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pricing.price(List.of(line("kebab", 1000)), OrderChannel.TABLE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownProductsAreRejectedWithoutEchoingTheRequest() {
        when(products.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pricing.price(List.of(line("ghost", 1)), OrderChannel.TABLE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("Hacked");
        assertThatThrownBy(() -> pricing.price(List.of(line(null, 1)), OrderChannel.TABLE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void guestsCannotOrderSoldOutProductsButTheCounterCanSellThem() {
        assertThatThrownBy(() -> pricing.price(List.of(line("soldout", 1)), OrderChannel.TABLE))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Falafel is sold out");
        assertThat(pricing.price(List.of(line("soldout", 1)), OrderChannel.COUNTER))
                .singleElement().satisfies(item -> assertThat(item.price()).isEqualByComparingTo("5.00"));
    }

    @Test
    void anEmptyCartStaysEmpty() {
        assertThat(pricing.price(List.of(), OrderChannel.TABLE)).isEmpty();
        assertThat(pricing.price(null, OrderChannel.TABLE)).isEmpty();
    }
}
