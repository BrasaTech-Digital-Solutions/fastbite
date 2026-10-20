package es.brasatech.fastbite.jpa.settings;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The single settings row of a restaurant schema. */
@Entity(name = "RestaurantSettings")
@Table(name = "restaurant_settings")
public class RestaurantSettingsEntity {

    static final int ID = 1;

    @Id
    private int id = ID;
    private boolean dineIn;
    private boolean takeaway;
    private int kdsYellowMinutes;
    private int kdsRedMinutes;
    /** Archives made before it existed restore with zero. */
    private java.math.BigDecimal cashFloat = java.math.BigDecimal.ZERO;
    private boolean tableQrTrusted;

    public boolean isDineIn() {
        return dineIn;
    }

    public void setDineIn(boolean dineIn) {
        this.dineIn = dineIn;
    }

    public boolean isTakeaway() {
        return takeaway;
    }

    public void setTakeaway(boolean takeaway) {
        this.takeaway = takeaway;
    }

    public int getKdsYellowMinutes() {
        return kdsYellowMinutes;
    }

    public void setKdsYellowMinutes(int kdsYellowMinutes) {
        this.kdsYellowMinutes = kdsYellowMinutes;
    }

    public int getKdsRedMinutes() {
        return kdsRedMinutes;
    }

    public void setKdsRedMinutes(int kdsRedMinutes) {
        this.kdsRedMinutes = kdsRedMinutes;
    }

    public java.math.BigDecimal getCashFloat() {
        return cashFloat;
    }

    public void setCashFloat(java.math.BigDecimal cashFloat) {
        this.cashFloat = cashFloat;
    }

    public boolean isTableQrTrusted() {
        return tableQrTrusted;
    }

    public void setTableQrTrusted(boolean tableQrTrusted) {
        this.tableQrTrusted = tableQrTrusted;
    }
}
