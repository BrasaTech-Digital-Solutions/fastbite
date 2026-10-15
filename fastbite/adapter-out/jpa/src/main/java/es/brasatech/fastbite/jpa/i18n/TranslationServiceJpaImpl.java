package es.brasatech.fastbite.jpa.i18n;

import es.brasatech.fastbite.application.office.I18nConfig;
import es.brasatech.fastbite.application.office.TranslationService;
import es.brasatech.fastbite.domain.I18nField;
import es.brasatech.fastbite.domain.TranslatableText;
import es.brasatech.fastbite.domain.TranslatableType;
import es.brasatech.fastbite.jpa.customization.CustomizationEntity;
import es.brasatech.fastbite.jpa.customization.CustomizationJpaRepository;
import es.brasatech.fastbite.jpa.customization.CustomizationOptionEntity;
import es.brasatech.fastbite.jpa.discount.DiscountRuleEntity;
import es.brasatech.fastbite.jpa.discount.DiscountRuleJpaRepository;
import es.brasatech.fastbite.jpa.group.GroupEntity;
import es.brasatech.fastbite.jpa.group.GroupJpaRepository;
import es.brasatech.fastbite.jpa.product.ProductEntity;
import es.brasatech.fastbite.jpa.product.ProductJpaRepository;
import es.brasatech.fastbite.jpa.table.TableEntity;
import es.brasatech.fastbite.jpa.table.TableJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static es.brasatech.fastbite.domain.TranslatableText.DESCRIPTION;
import static es.brasatech.fastbite.domain.TranslatableText.NAME;
import static es.brasatech.fastbite.domain.TranslatableText.OPTION;

/**
 * Translatable texts of the catalog: the default language is stored in each entity's own column,
 * the other languages in its translations column.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TranslationServiceJpaImpl implements TranslationService {

    /** A translatable text field: its key in the editor, its kind, and the entity column holding the default language. */
    private record Field<E extends Translatable>(String key, String kind, String stored,
                                                 Function<E, String> get, BiConsumer<E, String> set) {
    }

    private static <E extends Translatable> Field<E> field(String name, Function<E, String> get, BiConsumer<E, String> set) {
        return new Field<>(name, name, name, get, set);
    }

    private static final List<Field<GroupEntity>> GROUP_FIELDS = List.of(
            field(NAME, GroupEntity::getName, GroupEntity::setName),
            field(DESCRIPTION, GroupEntity::getDescription, GroupEntity::setDescription));
    private static final List<Field<ProductEntity>> PRODUCT_FIELDS = List.of(
            field(NAME, ProductEntity::getName, ProductEntity::setName),
            field(DESCRIPTION, ProductEntity::getDescription, ProductEntity::setDescription));
    private static final List<Field<CustomizationEntity>> CUSTOMIZATION_FIELDS = List.of(
            field(NAME, CustomizationEntity::getName, CustomizationEntity::setName));
    private static final List<Field<TableEntity>> TABLE_FIELDS = List.of(
            field(NAME, TableEntity::getName, TableEntity::setName));
    private static final List<Field<DiscountRuleEntity>> DISCOUNT_FIELDS = List.of(
            field(NAME, DiscountRuleEntity::getName, DiscountRuleEntity::setName));

    private static List<Field<CustomizationOptionEntity>> optionFields(CustomizationOptionEntity option) {
        return List.of(new Field<>(OPTION + "_" + option.getId(), OPTION, NAME,
                CustomizationOptionEntity::getName, CustomizationOptionEntity::setName));
    }

    private final GroupJpaRepository groupRepository;
    private final ProductJpaRepository productRepository;
    private final CustomizationJpaRepository customizationRepository;
    private final TableJpaRepository tableRepository;
    private final DiscountRuleJpaRepository discountRepository;
    private final I18nConfig i18nConfig;

    @Override
    public Optional<List<TranslatableText>> findTexts(TranslatableType type, String id) {
        return switch (type) {
            case GROUP -> groupRepository.findById(id).map(group -> read(group, GROUP_FIELDS));
            case PRODUCT -> productRepository.findById(id).map(product -> read(product, PRODUCT_FIELDS));
            case TABLE -> tableRepository.findById(id).map(table -> read(table, TABLE_FIELDS));
            case DISCOUNT -> discountRepository.findById(id).map(discount -> read(discount, DISCOUNT_FIELDS));
            case CUSTOMIZATION -> customizationRepository.findById(id).map(customization -> {
                List<TranslatableText> texts = new ArrayList<>(read(customization, CUSTOMIZATION_FIELDS));
                customization.getOptions().forEach(option -> texts.addAll(read(option, optionFields(option))));
                return texts;
            });
        };
    }

    @Override
    public boolean saveTexts(TranslatableType type, String id, Map<String, I18nField> texts) {
        return switch (type) {
            case GROUP -> groupRepository.findById(id).map(group -> write(group, GROUP_FIELDS, texts)).isPresent();
            case PRODUCT -> productRepository.findById(id).map(product -> write(product, PRODUCT_FIELDS, texts)).isPresent();
            case TABLE -> tableRepository.findById(id).map(table -> write(table, TABLE_FIELDS, texts)).isPresent();
            case DISCOUNT -> discountRepository.findById(id).map(discount -> write(discount, DISCOUNT_FIELDS, texts)).isPresent();
            case CUSTOMIZATION -> customizationRepository.findById(id).map(customization -> {
                write(customization, CUSTOMIZATION_FIELDS, texts);
                customization.getOptions().forEach(option -> write(option, optionFields(option), texts));
                return customization;
            }).isPresent();
        };
    }

    private <E extends Translatable> List<TranslatableText> read(E entity, List<Field<E>> fields) {
        return fields.stream()
                .map(field -> new TranslatableText(field.key(), field.kind(), current(entity, field)))
                .toList();
    }

    /** Sets the default-language columns and rebuilds the translations; texts that are not given keep their values. */
    private <E extends Translatable> E write(E entity, List<Field<E>> fields, Map<String, I18nField> texts) {
        String defaultLang = i18nConfig.getDefaultLanguage();
        Map<String, I18nField> byColumn = new HashMap<>();
        for (Field<E> field : fields) {
            I18nField value = texts.getOrDefault(field.key(), current(entity, field));
            field.set().accept(entity, value.get(defaultLang, defaultLang));
            byColumn.put(field.stored(), value);
        }
        entity.setTranslations(Translations.of(defaultLang, byColumn));
        return entity;
    }

    private <E extends Translatable> I18nField current(E entity, Field<E> field) {
        return Translations.toI18nField(entity.getTranslations(), field.stored(), i18nConfig.getDefaultLanguage(),
                field.get().apply(entity));
    }
}
