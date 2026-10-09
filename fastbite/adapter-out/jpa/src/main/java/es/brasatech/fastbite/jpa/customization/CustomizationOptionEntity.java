package es.brasatech.fastbite.jpa.customization;

import jakarta.persistence.*;

import es.brasatech.fastbite.domain.allergen.Allergen;
import es.brasatech.fastbite.jpa.allergen.AllergensConverter;

import java.math.BigDecimal;
import es.brasatech.fastbite.jpa.i18n.Translatable;
import es.brasatech.fastbite.jpa.i18n.TranslationsConverter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * JPA entity for Customization Option.
 * Each option is now a separate entity with its own translations.
 * This allows efficient translation lookups for orders without losing parent
 * context.
 */
@Entity
@Table(name = "customization_options", indexes = {
        @Index(name = "idx_option_customization", columnList = "customization_id"),
        @Index(name = "idx_option_order", columnList = "customization_id, option_index")
})
public class CustomizationOptionEntity implements Translatable {

    @Id
    private String id; // Format: "customizationId-opt-index"

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customization_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties("options")
    private CustomizationEntity customization;

    @Column(nullable = false)
    private String name; // Default language value

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private boolean isSelectedByDefault;

    @Column(nullable = false)
    private int defaultValue;

    @Column(name = "option_index", nullable = false)
    private int optionIndex; // To maintain order within customization

    /** Other languages of the text fields: language -> field -> text. */
    @Convert(converter = TranslationsConverter.class)
    @Column(name = "translations", columnDefinition = "text")
    private Map<String, Map<String, String>> translations = new HashMap<>();

    /** Allergens the option adds to the dish; null or empty adds none. */
    @Convert(converter = AllergensConverter.class)
    @Column(name = "allergens", length = 200)
    private Set<Allergen> allergens;

    public CustomizationOptionEntity() {
    }

    public CustomizationOptionEntity(String id, String name, BigDecimal price,
            boolean isSelectedByDefault, int defaultValue, int optionIndex) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.isSelectedByDefault = isSelectedByDefault;
        this.defaultValue = defaultValue;
        this.optionIndex = optionIndex;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public CustomizationEntity getCustomization() {
        return customization;
    }

    public void setCustomization(CustomizationEntity customization) {
        this.customization = customization;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public boolean isSelectedByDefault() {
        return isSelectedByDefault;
    }

    public void setSelectedByDefault(boolean selectedByDefault) {
        isSelectedByDefault = selectedByDefault;
    }

    public int getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(int defaultValue) {
        this.defaultValue = defaultValue;
    }

    public int getOptionIndex() {
        return optionIndex;
    }

    public void setOptionIndex(int optionIndex) {
        this.optionIndex = optionIndex;
    }

    @Override
    public Map<String, Map<String, String>> getTranslations() {
        return translations;
    }

    @Override
    public void setTranslations(Map<String, Map<String, String>> translations) {
        this.translations = translations != null ? translations : new HashMap<>();
    }

    public Set<Allergen> getAllergens() {
        return allergens;
    }

    public void setAllergens(Set<Allergen> allergens) {
        this.allergens = Allergen.copyOf(allergens);
    }
}
