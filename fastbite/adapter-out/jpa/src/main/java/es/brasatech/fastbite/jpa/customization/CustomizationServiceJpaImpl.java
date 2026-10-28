package es.brasatech.fastbite.jpa.customization;

import es.brasatech.fastbite.application.office.CustomizationService;
import es.brasatech.fastbite.application.office.I18nConfig;
import es.brasatech.fastbite.domain.allergen.Allergen;
import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import es.brasatech.fastbite.domain.customization.CustomizationDto;
import es.brasatech.fastbite.domain.customization.CustomizationOptionDto;
import es.brasatech.fastbite.jpa.i18n.Translations;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * JPA implementation of CustomizationService. Default-language texts live in the entities' columns,
 * other languages in their translations columns (customization and each option).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CustomizationServiceJpaImpl implements CustomizationService {

    private final CustomizationJpaRepository repository;
    private final I18nConfig i18nConfig;

    @Override
    public List<CustomizationDto> findAll() {
        String language = LocaleContextHolder.getLocale().getLanguage();
        return repository.findAll().stream().map(entity -> toDto(entity, language)).toList();
    }

    @Override
    public Optional<CustomizationDto> findById(String id) {
        return repository.findById(id).map(entity -> toDto(entity, LocaleContextHolder.getLocale().getLanguage()));
    }

    private void validateOptions(CustomizationDto customizationDto) {
        if (customizationDto.options() == null || customizationDto.options().isEmpty()) {
            throw new LocalizedRejection("customization.error.noOptions", "A customization must have at least one option");
        }
        if ("quantity".equalsIgnoreCase(customizationDto.type())) {
            for (CustomizationOptionDto opt : customizationDto.options()) {
                if (opt.defaultValue() < 0 || opt.defaultValue() > 1) {
                    throw new LocalizedRejection("customization.error.quantityDefaultValue",
                            "Default value for a quantity customization must be 0 or 1");
                }
            }
        }
    }

    @Override
    public CustomizationDto create(CustomizationDto customizationDto) {
        validateOptions(customizationDto);
        CustomizationEntity saved = repository.save(
                new CustomizationEntity(null, customizationDto.name(), customizationDto.type(), 0));
        replaceOptions(saved, customizationDto.options(), customizationDto.type());
        saved = repository.save(saved);
        return toDto(saved, i18nConfig.getDefaultLanguage());
    }

    @Override
    public Optional<CustomizationDto> update(String id, CustomizationDto customizationDto) {
        validateOptions(customizationDto);
        return repository.findById(id).map(existing -> {
            existing.setName(customizationDto.name());
            existing.setType(customizationDto.type());
            replaceOptions(existing, customizationDto.options(), customizationDto.type());
            return toDto(repository.save(existing), i18nConfig.getDefaultLanguage());
        });
    }

    @Override
    public boolean delete(String id) {
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        return true;
    }

    @Override
    public void clear() {
        repository.deleteAll();
    }

    /**
     * Recreates the options (ids "{customizationId}-opt-{index}"); each keeps the translations it had under the same id.
     */
    private void replaceOptions(CustomizationEntity customization, List<CustomizationOptionDto> options, String type) {
        Map<String, Map<String, Map<String, String>>> previousTranslations = customization.getOptions().stream()
                .collect(Collectors.toMap(CustomizationOptionEntity::getId, CustomizationOptionEntity::getTranslations));
        customization.clearOptions();
        boolean isQuantity = "quantity".equalsIgnoreCase(type);
        for (int i = 0; i < options.size(); i++) {
            CustomizationOptionDto option = options.get(i);
            String optionId = customization.getId() + "-opt-" + i;
            boolean isSelectedByDefault = isQuantity ? option.defaultValue() >= 1 : option.isSelectedByDefault();
            CustomizationOptionEntity entity = new CustomizationOptionEntity(
                    optionId, option.name(), option.price(), isSelectedByDefault, option.defaultValue(), i);
            entity.setTranslations(new HashMap<>(previousTranslations.getOrDefault(optionId, Map.of())));
            entity.setAllergens(option.allergens() == null || option.allergens().isEmpty() ? null : option.allergens());
            customization.addOption(entity);
        }
    }

    private CustomizationDto toDto(CustomizationEntity entity, String language) {
        List<CustomizationOptionDto> options = entity.getOptions().stream()
                .map(option -> new CustomizationOptionDto(
                        option.getId(),
                        Translations.get(option.getTranslations(), language, "name", option.getName()),
                        option.getPrice(),
                        option.isSelectedByDefault(),
                        option.getDefaultValue(),
                        Allergen.copyOf(option.getAllergens())))
                .toList();
        return new CustomizationDto(
                entity.getId(),
                Translations.get(entity.getTranslations(), language, "name", entity.getName()),
                entity.getType(),
                options,
                entity.getUsageCount());
    }
}
