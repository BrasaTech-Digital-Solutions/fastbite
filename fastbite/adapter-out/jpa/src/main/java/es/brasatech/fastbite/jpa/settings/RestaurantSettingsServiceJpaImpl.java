package es.brasatech.fastbite.jpa.settings;

import es.brasatech.fastbite.application.settings.RestaurantSettingsService;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RestaurantSettingsServiceJpaImpl implements RestaurantSettingsService {

    private final RestaurantSettingsJpaRepository repository;
    private final es.brasatech.fastbite.application.tenant.FreePlanLock freePlanLock;

    @org.springframework.beans.factory.annotation.Autowired
    public RestaurantSettingsServiceJpaImpl(
            RestaurantSettingsJpaRepository repository,
            es.brasatech.fastbite.application.tenant.FreePlanLock freePlanLock) {
        this.repository = java.util.Objects.requireNonNull(repository, "repository");
        this.freePlanLock = java.util.Objects.requireNonNull(freePlanLock, "freePlanLock");
    }

    @Override
    @Transactional(readOnly = true)
    public RestaurantSettings get() {
        boolean forceTakeaway = freePlanLock.locked(es.brasatech.fastbite.domain.tenant.TenantContext.getCurrentTenant());
        return repository.findById(RestaurantSettingsEntity.ID)
                .map(entity -> new RestaurantSettings(entity.isDineIn(), forceTakeaway || entity.isTakeaway(),
                        entity.getKdsYellowMinutes(), entity.getKdsRedMinutes(), entity.isTableQrTrusted()))
                .orElse(RestaurantSettings.DEFAULTS);
    }

    @Override
    public RestaurantSettings save(RestaurantSettings settings) {
        if (freePlanLock.locked(es.brasatech.fastbite.domain.tenant.TenantContext.getCurrentTenant()) && !settings.takeaway()) {
            throw new IllegalArgumentException("The Free Plan requires online takeaway orders to stay enabled.");
        }
        RestaurantSettingsEntity entity = repository.findById(RestaurantSettingsEntity.ID)
                .orElseGet(RestaurantSettingsEntity::new);
        entity.setDineIn(settings.dineIn());
        entity.setTakeaway(settings.takeaway());
        entity.setKdsYellowMinutes(settings.kdsYellowMinutes());
        entity.setKdsRedMinutes(settings.kdsRedMinutes());
        entity.setTableQrTrusted(settings.tableQrTrusted());
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
            fresh.setTableQrTrusted(RestaurantSettings.DEFAULTS.tableQrTrusted());
            return fresh;
        });
        entity.setCashFloat(cashFloat.setScale(2, java.math.RoundingMode.HALF_UP));
        repository.save(entity);
    }
}
