package es.brasatech.fastbite.jpa.payment;

import es.brasatech.fastbite.application.payment.PaymentService;
import es.brasatech.fastbite.domain.payment.MoneyDenomination;
import es.brasatech.fastbite.domain.payment.PaymentConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class PaymentServiceJpaImpl implements PaymentService {
    private final PaymentConfigJpaRepository repository;

    @Override
    public PaymentConfig getActiveConfig() {
        return repository.findAll().stream()
                .filter(PaymentConfigEntity::isActive)
                .findFirst()
                .map(this::toDomain)
                .orElseGet(() -> {
                    PaymentConfig config = new PaymentConfig(new ArrayList<>(), new ArrayList<>());
                    saveConfig(config);
                    setActiveConfig(config.id());
                    return config;
                });
    }

    @Override
    public List<PaymentConfig> findAllConfigs() {
        return repository.findAll().stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<PaymentConfig> findConfigById(String id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public void saveConfig(PaymentConfig config) {
        PaymentConfigEntity entity = repository.findById(config.id())
                .orElse(new PaymentConfigEntity());
        entity.setId(config.id());
        entity.setActiveModes(new ArrayList<>(config.activeModes()));
        entity.setMoneyDenominations(config.moneyDenominations().stream()
                .map(this::toEmbeddable)
                .collect(Collectors.toList()));
        entity.setActive(config.active());

        repository.save(entity);
    }

    @Override
    public void deleteConfig(String id) {
        repository.deleteById(id);
    }

    @Override
    public void setActiveConfig(String id) {
        repository.findAll().forEach(config -> {
            config.setActive(config.getId().equals(id));
            repository.save(config);
        });
    }

    private PaymentConfig toDomain(PaymentConfigEntity entity) {
        List<MoneyDenomination> denominations = entity.getMoneyDenominations().stream()
                .map(emb -> new MoneyDenomination(emb.getValue(), emb.getImage(), emb.getType()))
                .toList();
        return new PaymentConfig(entity.getId(), new ArrayList<>(entity.getActiveModes()), denominations, entity.isActive());
    }

    private MoneyDenominationEmbeddable toEmbeddable(MoneyDenomination domain) {
        MoneyDenominationEmbeddable emb = new MoneyDenominationEmbeddable();
        emb.setValue(domain.value());
        emb.setImage(domain.image());
        emb.setType(domain.type());
        return emb;
    }
}
