package es.brasatech.fastbite.jpa.order;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.List;

@Entity(name = "CartItem")
@Table(name = "cart_items")
public class CartItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    private String itemId;
    private String name;
    private String description;
    private String image;
    private int quantity;

    @ElementCollection
    @CollectionTable(name = "cart_item_customizations", joinColumns = @JoinColumn(name = "cart_item_id"))
    private List<ProductCustomizerEntity> customizations;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /** The VAT rate the line was charged at; null on lines from before rates were kept. */
    @Column(name = "vat_rate", precision = 5, scale = 2)
    private BigDecimal vatRate;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public List<ProductCustomizerEntity> getCustomizations() {
        return customizations;
    }

    public void setCustomizations(List<ProductCustomizerEntity> customizations) {
        this.customizations = customizations;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getVatRate() {
        return vatRate;
    }

    public void setVatRate(BigDecimal vatRate) {
        this.vatRate = vatRate;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

}
