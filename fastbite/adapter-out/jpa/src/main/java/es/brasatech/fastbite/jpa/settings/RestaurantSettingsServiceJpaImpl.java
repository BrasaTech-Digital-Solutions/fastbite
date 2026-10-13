package es.brasatech.fastbite.jpa.settings;

import es.brasatech.fastbite.application.settings.RestaurantSettingsService;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("jpa")
@RequiredArgsConstructor
@Transactional
public class RestaurantSettingsServiceJpaImpl implements RestaurantSettingsService {

    private final RestaurantSettingsJpaRepository repository;

    @Override
    @Transactional(readOnly = true)
    public RestaurantSettings get() {
        return repository.findById(RestaurantSettingsEntity.ID)
                .map(entity -> new RestaurantSettings(entity.isDineIn(), entity.isTakeaway(),
                        entity.getKdsYellowMinutes(), entity.getKdsRedMinutes()))
                .orElse(RestaurantSettings.DEFAULTS);
    }

    @Override
    public RestaurantSettings save(RestaurantSettings settings) {
        RestaurantSettingsEntity entity = repository.findById(RestaurantSettingsEntity.ID)
                .orElseGet(RestaurantSettingsEntity::new);
        entity.setDineIn(settings.dineIn());
        entity.setTakeaway(settings.takeaway());
        entity.setKdsYellowMinutes(settings.kdsYellowMinutes());
        entity.setKdsRedMinutes(settings.kdsRedMinutes());
        repository.save(entity);
        return settings;
    }

    @Override
    @Transactional(readOnly = true)
    public java.math.BigDecimal cashFloat() {
        return repository.findById(RestaurantSettingsEntity.ID).map(RestaurantSettingsEntity::getCashFloat)
                .filter(java.util.Objects::nonNull).orElse(java.math.BigDecimal.ZERO).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public void saveCashFloat(java.math.BigDecimal cashFloat) {
        RestaurantSettingsEntity entity = repository.findById(RestaurantSettingsEntity.ID).orElseGet(() -> {
            // The first thing saved: the other settings start as their defaults
            RestaurantSettingsEntity fresh = new RestaurantSettingsEntity();
            fresh.setDineIn(RestaurantSettings.DEFAULTS.dineIn());
            fresh.setTakeaway(RestaurantSettings.DEFAULTS.takeaway());
            return fresh;
        });
        entity.setCashFloat(cashFloat.setScale(2, java.math.RoundingMode.HALF_UP));
        repository.save(entity);
    }
}
