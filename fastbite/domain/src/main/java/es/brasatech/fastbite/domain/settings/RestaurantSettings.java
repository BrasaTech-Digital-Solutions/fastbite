package es.brasatech.fastbite.domain.settings;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import es.brasatech.fastbite.domain.order.ServiceType;

/**
 * What one restaurant has switched on. The KDS minutes colour a kitchen ticket yellow and then
 * red as it waits; 0 turns the colouring off.
 */
public record RestaurantSettings(boolean dineIn, boolean takeaway, int kdsYellowMinutes, int kdsRedMinutes, boolean tableQrTrusted) {

    public static final RestaurantSettings DEFAULTS = new RestaurantSettings(true, true, 0, 0, false);

    public RestaurantSettings {
        kdsYellowMinutes = Math.max(0, kdsYellowMinutes);
        kdsRedMinutes = Math.max(0, kdsRedMinutes);
    }

    public RestaurantSettings(boolean dineIn, boolean takeaway, int kdsYellowMinutes, int kdsRedMinutes) {
        this(dineIn, takeaway, kdsYellowMinutes, kdsRedMinutes, false);
    }

    @JsonCreator
    public static RestaurantSettings fromJson(
            @JsonProperty("dineIn") boolean dineIn,
            @JsonProperty("takeaway") boolean takeaway,
            @JsonProperty("kdsYellowMinutes") int kdsYellowMinutes,
            @JsonProperty("kdsRedMinutes") int kdsRedMinutes,
            @JsonProperty("tableQrTrusted") Boolean tableQrTrusted) {
        return new RestaurantSettings(dineIn, takeaway, kdsYellowMinutes, kdsRedMinutes, Boolean.TRUE.equals(tableQrTrusted));
    }

    public boolean offers(ServiceType serviceType) {
        return serviceType == ServiceType.TAKEAWAY ? takeaway : dineIn;
    }
}
