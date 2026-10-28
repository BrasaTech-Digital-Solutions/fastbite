package es.brasatech.fastbite.jpa.customization;

import es.brasatech.fastbite.application.office.I18nConfig;
import es.brasatech.fastbite.domain.customization.CustomizationDto;
import es.brasatech.fastbite.domain.customization.CustomizationOptionDto;
import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomizationServiceJpaImplTest {

    @Mock
    private CustomizationJpaRepository repository;

    @Mock
    private I18nConfig i18nConfig;

    private CustomizationServiceJpaImpl service;

    @BeforeEach
    void setUp() {
        service = new CustomizationServiceJpaImpl(repository, i18nConfig);
    }

    @Test
    void create_rejectsNullOrEmptyOptions() {
        var withNull = new CustomizationDto(null, "Size", "radio", null, 0);
        var withEmpty = new CustomizationDto(null, "Size", "radio", List.of(), 0);

        assertThatThrownBy(() -> service.create(withNull))
                .isInstanceOf(LocalizedRejection.class)
                .satisfies(e -> assertThat(((LocalizedRejection) e).key()).isEqualTo("customization.error.noOptions"));

        assertThatThrownBy(() -> service.create(withEmpty))
                .isInstanceOf(LocalizedRejection.class)
                .satisfies(e -> assertThat(((LocalizedRejection) e).key()).isEqualTo("customization.error.noOptions"));
    }

    @Test
    void update_rejectsNullOrEmptyOptions() {
        var withNull = new CustomizationDto("c-1", "Size", "radio", null, 0);
        var withEmpty = new CustomizationDto("c-1", "Size", "radio", List.of(), 0);

        assertThatThrownBy(() -> service.update("c-1", withNull))
                .isInstanceOf(LocalizedRejection.class)
                .satisfies(e -> assertThat(((LocalizedRejection) e).key()).isEqualTo("customization.error.noOptions"));

        assertThatThrownBy(() -> service.update("c-1", withEmpty))
                .isInstanceOf(LocalizedRejection.class)
                .satisfies(e -> assertThat(((LocalizedRejection) e).key()).isEqualTo("customization.error.noOptions"));
    }

    @Test
    void create_quantityType_rejectsDefaultValueAboveOne() {
        var dto = new CustomizationDto(null, "Toppings", "quantity", List.of(
                new CustomizationOptionDto("opt-1", "Cheese", BigDecimal.ONE, true, 2)
        ), 0);

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(LocalizedRejection.class)
                .satisfies(e -> assertThat(((LocalizedRejection) e).key()).isEqualTo("customization.error.quantityDefaultValue"));
    }

    @Test
    void create_quantityType_rejectsNegativeDefaultValue() {
        var dto = new CustomizationDto(null, "Toppings", "quantity", List.of(
                new CustomizationOptionDto("opt-1", "Cheese", BigDecimal.ONE, true, -1)
        ), 0);

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(LocalizedRejection.class)
                .satisfies(e -> assertThat(((LocalizedRejection) e).key()).isEqualTo("customization.error.quantityDefaultValue"));
    }

    @Test
    void update_quantityType_rejectsDefaultValueAboveOne() {
        var dto = new CustomizationDto("c-1", "Toppings", "quantity", List.of(
                new CustomizationOptionDto("opt-1", "Cheese", BigDecimal.ONE, true, 3)
        ), 0);

        assertThatThrownBy(() -> service.update("c-1", dto))
                .isInstanceOf(LocalizedRejection.class)
                .satisfies(e -> assertThat(((LocalizedRejection) e).key()).isEqualTo("customization.error.quantityDefaultValue"));
    }

    @Test
    void create_quantityType_acceptsDefaultZeroAndOne_andNormalizesSelectedByDefault() {
        when(i18nConfig.getDefaultLanguage()).thenReturn("es");
        when(repository.save(any(CustomizationEntity.class))).thenAnswer(inv -> {
            CustomizationEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                return new CustomizationEntity("c-1", entity.getName(), entity.getType(), 0);
            }
            return entity;
        });

        var dto = new CustomizationDto(null, "Ingredients", "quantity", List.of(
                new CustomizationOptionDto(null, "Onions", BigDecimal.ZERO, false, 1),
                new CustomizationOptionDto(null, "Extra Bacon", new BigDecimal("1.50"), true, 0)
        ), 0);

        CustomizationDto created = service.create(dto);

        assertThat(created).isNotNull();
        ArgumentCaptor<CustomizationEntity> captor = ArgumentCaptor.forClass(CustomizationEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());

        CustomizationEntity finalSaved = captor.getValue();
        assertThat(finalSaved.getOptions()).hasSize(2);
        // Option 0: defaultValue=1 -> isSelectedByDefault normalized to true
        assertThat(finalSaved.getOptions().get(0).getDefaultValue()).isEqualTo(1);
        assertThat(finalSaved.getOptions().get(0).isSelectedByDefault()).isTrue();
        // Option 1: defaultValue=0 -> isSelectedByDefault normalized to false
        assertThat(finalSaved.getOptions().get(1).getDefaultValue()).isEqualTo(0);
        assertThat(finalSaved.getOptions().get(1).isSelectedByDefault()).isFalse();
    }

    @Test
    void create_checkboxType_allowsDefaultAboveOne() {
        when(i18nConfig.getDefaultLanguage()).thenReturn("es");
        when(repository.save(any(CustomizationEntity.class))).thenAnswer(inv -> {
            CustomizationEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                return new CustomizationEntity("c-1", entity.getName(), entity.getType(), 0);
            }
            return entity;
        });

        var dto = new CustomizationDto(null, "Extras", "checkbox", List.of(
                new CustomizationOptionDto(null, "Sauce pack", BigDecimal.ONE, true, 2)
        ), 0);

        CustomizationDto created = service.create(dto);
        assertThat(created).isNotNull();
    }
}
