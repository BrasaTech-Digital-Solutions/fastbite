package es.brasatech.fastbite.jpa.product;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import es.brasatech.fastbite.jpa.i18n.Translatable;
import es.brasatech.fastbite.jpa.i18n.TranslationsConverter;
import java.util.HashMap;
import java.util.Map;

/**
 * JPA entity for Product.
 * Stores default language values directly.
 * Translations for other languages are in the translations column.
 */
@Entity(name = "Product")
@Table(name = "products")
public class ProductEntity implements Translatable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(length = 1000)
    private String description;

    private String image;

    @ElementCollection
    @CollectionTable(name = "product_customizations", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "customization_id")
    private Set<String> customizations = new HashSet<>();

    @Column(nullable = false)
    private boolean active = true;

    /** The product's own VAT rate; null means the location's default rate. */
    @Column(name = "vat_rate", precision = 5, scale = 2)
    private BigDecimal vatRate;

    /** Other languages of the text fields: language -> field -> text. */
    @Convert(converter = TranslationsConverter.class)
    @Column(name = "translations", columnDefinition = "text")
    private Map<String, Map<String, String>> translations = new HashMap<>();

    public ProductEntity() {
    }

    public ProductEntity(String id, String name, BigDecimal price, String description,
            String image, Set<String> customizations, boolean active) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.description = description;
        this.image = image;
        this.customizations = customizations;
        this.active = active;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public Set<String> getCustomizations() {
        return customizations;
    }

    public void setCustomizations(Set<String> customizations) {
        this.customizations = customizations;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public BigDecimal getVatRate() {
        return vatRate;
    }

    public void setVatRate(BigDecimal vatRate) {
        this.vatRate = vatRate;
    }

    @Override
    public Map<String, Map<String, String>> getTranslations() {
        return translations;
    }

    @Override
    public void setTranslations(Map<String, Map<String, String>> translations) {
        this.translations = translations != null ? translations : new HashMap<>();
    }
}
