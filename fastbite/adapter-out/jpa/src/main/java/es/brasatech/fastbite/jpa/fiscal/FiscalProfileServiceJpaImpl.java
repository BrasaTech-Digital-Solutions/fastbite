package es.brasatech.fastbite.jpa.fiscal;

import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
public class FiscalProfileServiceJpaImpl implements FiscalProfileService {

    private final FiscalProfileJpaRepository repository;

    @Override
    @Transactional(readOnly = true)
    public FiscalProfile get() {
        return repository.findById(FiscalProfileEntity.ID)
                .map(FiscalProfileServiceJpaImpl::toProfile)
                .orElse(FiscalProfile.EMPTY);
    }

    @Override
    public FiscalProfile save(FiscalProfile profile) {
        FiscalProfileEntity entity = repository.findById(FiscalProfileEntity.ID).orElseGet(FiscalProfileEntity::new);
        entity.setLegalName(profile.legalName());
        entity.setTradeName(profile.tradeName());
        entity.setTaxId(profile.taxId());
        entity.setAddressLine(profile.addressLine());
        entity.setPostalCode(profile.postalCode());
        entity.setCity(profile.city());
        entity.setProvince(profile.province());
        entity.setPhone(profile.phone());
        entity.setEmail(profile.email());
        entity.setSeriesPrefix(profile.seriesPrefix());
        entity.setDefaultVatRate(profile.defaultVatRate());
        entity.setUpdatedAt(LocalDateTime.now());
        return toProfile(repository.save(entity));
    }

    static FiscalProfile toProfile(FiscalProfileEntity entity) {
        return new FiscalProfile(entity.getLegalName(), entity.getTradeName(), entity.getTaxId(),
                entity.getAddressLine(), entity.getPostalCode(), entity.getCity(), entity.getProvince(),
                entity.getPhone(), entity.getEmail(), entity.getSeriesPrefix(), entity.getDefaultVatRate());
    }
}
