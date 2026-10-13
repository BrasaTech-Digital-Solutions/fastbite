package es.brasatech.fastbite.application.settings;

import es.brasatech.fastbite.domain.settings.RestaurantSettings;

/** Settings of the restaurant the current request is for. */
public interface RestaurantSettingsService {

    /** The saved settings, or {@link RestaurantSettings#DEFAULTS} if the restaurant never saved any. */
    RestaurantSettings get();

    RestaurantSettings save(RestaurantSettings settings);

    /** The cash the drawer starts each day with, as the owner set it; zero if never set. */
    java.math.BigDecimal cashFloat();

    /** Kept apart from the other settings: saving those never changes it. */
    void saveCashFloat(java.math.BigDecimal cashFloat);
}
