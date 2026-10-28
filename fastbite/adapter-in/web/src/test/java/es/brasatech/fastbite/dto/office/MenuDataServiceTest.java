package es.brasatech.fastbite.dto.office;

import es.brasatech.fastbite.application.office.CustomizationService;
import es.brasatech.fastbite.application.office.GroupService;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.domain.customization.CustomizationDto;
import es.brasatech.fastbite.domain.customization.CustomizationOptionDto;
import es.brasatech.fastbite.dto.menu.Customization;
import es.brasatech.fastbite.dto.menu.CustomizationInputType;
import es.brasatech.fastbite.dto.menu.MenuData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MenuDataServiceTest {

    private final GroupService groupService = mock(GroupService.class);
    private final CustomizationService customizationService = mock(CustomizationService.class);
    private final ProductService productService = mock(ProductService.class);
    private final MessageSource messageSource = mock(MessageSource.class);
    private final MenuDataService menuDataService = new MenuDataService(
            groupService, customizationService, productService, messageSource);

    @BeforeEach
    void setUp() {
        when(groupService.findAll()).thenReturn(List.of());
        when(productService.findAll()).thenReturn(List.of());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("text");
    }

    @Test
    void mapsCustomizationTypesToMenuInputTypes() {
        List<CustomizationDto> dtos = List.of(
                new CustomizationDto("c-radio", "Size", "radio", List.of(
                        new CustomizationOptionDto("opt-1", "Regular", BigDecimal.ZERO, true, 1)), 0),
                new CustomizationDto("c-checkbox", "Toppings", "checkbox", List.of(
                        new CustomizationOptionDto("opt-2", "Cheese", BigDecimal.ONE, false, 0)), 0),
                new CustomizationDto("c-quantity", "Ingredients", "quantity", List.of(
                        new CustomizationOptionDto("opt-3", "Pickles", BigDecimal.ZERO, true, 1)), 0)
        );
        when(customizationService.findAll()).thenReturn(dtos);

        MenuData menuData = menuDataService.buildMenuData(Locale.ENGLISH);

        List<Customization> customizations = menuData.customizations();
        assertThat(customizations).hasSize(3);

        Customization radio = customizations.stream().filter(c -> c.id().equals("c-radio")).findFirst().orElseThrow();
        assertThat(radio.inputType()).isEqualTo(CustomizationInputType.RADIO);

        Customization checkbox = customizations.stream().filter(c -> c.id().equals("c-checkbox")).findFirst().orElseThrow();
        assertThat(checkbox.inputType()).isEqualTo(CustomizationInputType.CHECKBOX);

        Customization quantity = customizations.stream().filter(c -> c.id().equals("c-quantity")).findFirst().orElseThrow();
        assertThat(quantity.inputType()).isEqualTo(CustomizationInputType.HIDDEN);
    }
}
