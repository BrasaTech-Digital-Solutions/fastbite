package es.brasatech.fastbite.jpa.settings;

import es.brasatech.fastbite.application.settings.RestaurantSettingsService;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.OrderStatus;
import es.brasatech.fastbite.domain.order.ServiceType;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.order.OrderEntity;
import es.brasatech.fastbite.jpa.order.OrderJpaRepository;
import es.brasatech.fastbite.jpa.tenant.TenantProvisionerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = es.brasatech.fastbite.jpa.TestConfig.class)
@Import(RestaurantSettingsServiceJpaImpl.class)
class RestaurantSettingsAndTakeawayIntegrationTest {

    @Autowired
    private TenantProvisionerAdapter provisioner;
    @Autowired
    private RestaurantSettingsService settings;
    @Autowired
    private OrderJpaRepository orders;
    @Autowired
    private TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private <T> T inTenant(String tenant, Supplier<T> work) {
        TenantContext.setCurrentTenant(tenant);
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aRestaurantThatNeverSavedSettingsUsesTheDefaults() {
        provisioner.provisionTenant("settingsnew");

        assertThat(inTenant("settingsnew", settings::get)).isEqualTo(RestaurantSettings.DEFAULTS);
        assertThat(RestaurantSettings.DEFAULTS.offers(ServiceType.DINE_IN)).isTrue();
        assertThat(RestaurantSettings.DEFAULTS.offers(ServiceType.TAKEAWAY)).isTrue();
    }

    @Test
    void eachRestaurantKeepsItsOwnSettings() {
        provisioner.provisionTenant("settingsa");
        provisioner.provisionTenant("settingsb");

        inTenant("settingsa", () -> settings.save(new RestaurantSettings(true, false, 5, 10)));
        inTenant("settingsb", () -> settings.save(new RestaurantSettings(false, true, 0, 0)));
        inTenant("settingsa", () -> settings.save(new RestaurantSettings(true, false, 8, 15)));

        assertThat(inTenant("settingsa", settings::get)).isEqualTo(new RestaurantSettings(true, false, 8, 15));
        assertThat(inTenant("settingsb", settings::get)).isEqualTo(new RestaurantSettings(false, true, 0, 0));
        assertThat(inTenant("settingsb", settings::get).offers(ServiceType.DINE_IN)).isFalse();
    }

    @Test
    void negativeTimerMinutesAreSavedAsOff() {
        provisioner.provisionTenant("settingsneg");

        inTenant("settingsneg", () -> settings.save(new RestaurantSettings(true, true, -3, -1)));

        assertThat(inTenant("settingsneg", settings::get)).isEqualTo(new RestaurantSettings(true, true, 0, 0));
    }

    @Test
    void onlyUnpaidOpenTakeawayOrdersWaitForPaymentAtPickup() {
        provisioner.provisionTenant("pickup");
        inTenant("pickup", () -> tx.execute(status -> orders.saveAll(List.of(
                order(1, ServiceType.TAKEAWAY, OrderPaymentStatus.UNPAID, OrderStatus.CREATED),
                order(2, ServiceType.TAKEAWAY, OrderPaymentStatus.UNPAID, OrderStatus.DONE),
                order(3, ServiceType.TAKEAWAY, OrderPaymentStatus.PAID, OrderStatus.CREATED),
                order(4, ServiceType.TAKEAWAY, OrderPaymentStatus.UNPAID, OrderStatus.CANCELLED),
                order(5, ServiceType.TAKEAWAY, OrderPaymentStatus.UNPAID, OrderStatus.COMPLETE),
                order(6, ServiceType.DINE_IN, OrderPaymentStatus.UNPAID, OrderStatus.CREATED),
                order(7, null, OrderPaymentStatus.UNPAID, OrderStatus.CREATED))))); // saved before service types existed

        List<Integer> waiting = inTenant("pickup", () -> tx.execute(status ->
                orders.findByServiceTypeAndPaymentStatusAndStatusNotInOrderByCreatedAt(ServiceType.TAKEAWAY,
                                OrderPaymentStatus.UNPAID, List.of(OrderStatus.COMPLETE, OrderStatus.CANCELLED))
                        .stream().map(OrderEntity::getOrderNumber).toList()));

        assertThat(waiting).containsExactly(1, 2);
    }

    private static OrderEntity order(int number, ServiceType serviceType, OrderPaymentStatus payment, OrderStatus status) {
        OrderEntity entity = new OrderEntity();
        entity.setOrderNumber(number);
        entity.setCreatedAt(LocalDateTime.of(2026, 9, 30, 12, number));
        entity.setUpdatedAt(entity.getCreatedAt());
        entity.setStatus(status);
        entity.setTotal(BigDecimal.TEN);
        entity.setPaymentStatus(payment);
        entity.setOrderChannel(OrderChannel.COUNTER);
        entity.setOrderLanguage("en");
        entity.setServiceType(serviceType);
        entity.setItems(new ArrayList<>());
        return entity;
    }
}
