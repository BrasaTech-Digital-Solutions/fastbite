package es.brasatech.fastbite.jpa.table;

import jakarta.persistence.*;
import es.brasatech.fastbite.domain.table.TableStatus;
import es.brasatech.fastbite.jpa.i18n.Translatable;
import es.brasatech.fastbite.jpa.i18n.TranslationsConverter;
import java.util.HashMap;
import java.util.Map;

@Entity(name = "DiningTable")
@Table(name = "dining_tables")
public class TableEntity implements Translatable {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    private String name;
    private int seats;
    @Enumerated(EnumType.STRING)
    private TableStatus status;
    private boolean active;

    @Column(name = "qr_trusted")
    private Boolean qrTrusted;

    @Column(name = "qr_trusted_by")
    private String qrTrustedBy;

    @Column(name = "qr_trusted_at")
    private java.time.LocalDateTime qrTrustedAt;

    @ElementCollection
    @CollectionTable(name = "table_orders", joinColumns = @JoinColumn(name = "table_id"))
    @Column(name = "order_id")
    private java.util.List<String> orderIds = new java.util.ArrayList<>();

    /** Other languages of the text fields: language -> field -> text. */
    @Convert(converter = TranslationsConverter.class)
    @Column(name = "translations", columnDefinition = "text")
    private Map<String, Map<String, String>> translations = new HashMap<>();

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

    public int getSeats() {
        return seats;
    }

    public void setSeats(int seats) {
        this.seats = seats;
    }

    public TableStatus getStatus() {
        return status;
    }

    public void setStatus(TableStatus status) {
        this.status = status;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Boolean getQrTrusted() {
        return qrTrusted;
    }

    public void setQrTrusted(Boolean qrTrusted) {
        this.qrTrusted = qrTrusted;
    }

    public String getQrTrustedBy() {
        return qrTrustedBy;
    }

    public void setQrTrustedBy(String qrTrustedBy) {
        this.qrTrustedBy = qrTrustedBy;
    }

    public java.time.LocalDateTime getQrTrustedAt() {
        return qrTrustedAt;
    }

    public void setQrTrustedAt(java.time.LocalDateTime qrTrustedAt) {
        this.qrTrustedAt = qrTrustedAt;
    }

    public java.util.List<String> getOrderIds() {
        return orderIds;
    }

    public void setOrderIds(java.util.List<String> orderIds) {
        this.orderIds = orderIds;
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
