package es.brasatech.fastbite.jpa.order;

import es.brasatech.fastbite.application.order.OrderNumberService;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.tenant.TenantProvisionerAdapter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = es.brasatech.fastbite.jpa.TestConfig.class)
@Import(OrderNumberServiceJpaImpl.class)
class OrderNumberServiceJpaImplTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    @Autowired
    private TenantProvisionerAdapter provisioner;
    @Autowired
    private OrderNumberService orderNumberService;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private int next(String tenant) {
        TenantContext.setCurrentTenant(tenant);
        return orderNumberService.next();
    }

    private int nextAt(String tenant, String instant) {
        var service = new OrderNumberServiceJpaImpl(entityManager, Clock.fixed(Instant.parse(instant), MADRID));
        TenantContext.setCurrentTenant(tenant);
        return tx.execute(status -> service.next());
    }

    @Test
    void eachRestaurantCountsItsOwnOrders() {
        provisioner.provisionTenant("numbersa");
        provisioner.provisionTenant("numbersb");

        assertThat(List.of(next("numbersa"), next("numbersa"), next("numbersb"), next("numbersa")))
                .containsExactly(1, 2, 1, 3);
    }

    @Test
    void numberingRestartsEachDayInTheRestaurantsTimeZone() {
        provisioner.provisionTenant("numbersdaily");

        assertThat(nextAt("numbersdaily", "2026-09-28T20:00:00Z")).isEqualTo(1);
        assertThat(nextAt("numbersdaily", "2026-09-28T21:30:00Z")).as("23:30 in Madrid, same day").isEqualTo(2);
        assertThat(nextAt("numbersdaily", "2026-09-28T22:30:00Z")).as("00:30 in Madrid, next day").isEqualTo(1);
        assertThat(nextAt("numbersdaily", "2026-09-29T10:00:00Z")).isEqualTo(2);
    }

    @Test
    void ordersPlacedAtTheSameTimeNeverShareANumber() throws Exception {
        provisioner.provisionTenant("numbersrush");
        Callable<Integer> order = () -> {
            try {
                return next("numbersrush");
            } finally {
                TenantContext.clear();
            }
        };

        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> numbers = pool.invokeAll(IntStream.range(0, 40).mapToObj(i -> order).toList());
            List<Integer> taken = numbers.stream().map(OrderNumberServiceJpaImplTest::get).toList();
            assertThat(taken).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 40).boxed().toList());
        }
    }

    private static int get(Future<Integer> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
