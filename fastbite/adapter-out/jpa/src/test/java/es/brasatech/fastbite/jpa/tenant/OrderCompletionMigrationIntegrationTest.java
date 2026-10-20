package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.OrderStatus;
import es.brasatech.fastbite.domain.order.ServiceType;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.order.OrderEntity;
import es.brasatech.fastbite.jpa.order.OrderJpaRepository;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = es.brasatech.fastbite.jpa.TestConfig.class)
class OrderCompletionMigrationIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private OrderJpaRepository orderRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void v20MigrationCompletesDeliveredAndPaidOrdersWhileLeavingOthersUntouched() {
        String tenantId = "v20test";
        String schema = "TENANT_" + tenantId.toUpperCase(Locale.ROOT);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + schema);

        // 1. Migrate tenant schema up to V19 (before V20)
        String[] locations = new String[]{"classpath:db/migration/restaurant"};
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema(schema)
                .schemas(schema)
                .locations(locations)
                .resourceProvider(new MigrationResourceProvider(locations))
                .javaMigrationClassProvider(List::of)
                .javaMigrations(new V3__LegacyTranslations())
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .failOnMissingLocations(true)
                .ignoreMigrationPatterns(new String[0])
                .target("19")
                .load()
                .migrate();

        // 2. Insert test orders through OrderEntity to verify against JPA entity mapping
        TenantContext.setCurrentTenant(tenantId);

        String deliveredPaidId;
        String deliveredUnpaidId;
        String acceptedPaidId;
        String donePaidId;

        var txTemplate = new TransactionTemplate(transactionManager);
        deliveredPaidId = txTemplate.execute(status -> {
            OrderEntity o1 = createEntity(1, OrderStatus.DELIVERED, OrderPaymentStatus.PAID);
            OrderEntity o2 = createEntity(2, OrderStatus.DELIVERED, OrderPaymentStatus.UNPAID);
            OrderEntity o3 = createEntity(3, OrderStatus.ACCEPTED, OrderPaymentStatus.PAID);
            OrderEntity o4 = createEntity(4, OrderStatus.DONE, OrderPaymentStatus.PAID);

            orderRepository.save(o1);
            orderRepository.save(o2);
            orderRepository.save(o3);
            orderRepository.save(o4);
            entityManager.flush();
            entityManager.clear();

            return o1.getId();
        });

        deliveredUnpaidId = txTemplate.execute(status -> orderRepository.findAll().stream()
                .filter(o -> o.getOrderNumber() == 2).findFirst().orElseThrow().getId());
        acceptedPaidId = txTemplate.execute(status -> orderRepository.findAll().stream()
                .filter(o -> o.getOrderNumber() == 3).findFirst().orElseThrow().getId());
        donePaidId = txTemplate.execute(status -> orderRepository.findAll().stream()
                .filter(o -> o.getOrderNumber() == 4).findFirst().orElseThrow().getId());

        // Verify initial state in database before V20
        assertThat(jdbc.queryForObject("SELECT status FROM " + schema + ".orders WHERE id = ?", Integer.class, deliveredPaidId))
                .isEqualTo(OrderStatus.DELIVERED.ordinal());
        assertThat(jdbc.queryForObject("SELECT status FROM " + schema + ".orders WHERE id = ?", Integer.class, deliveredUnpaidId))
                .isEqualTo(OrderStatus.DELIVERED.ordinal());
        assertThat(jdbc.queryForObject("SELECT status FROM " + schema + ".orders WHERE id = ?", Integer.class, acceptedPaidId))
                .isEqualTo(OrderStatus.ACCEPTED.ordinal());
        assertThat(jdbc.queryForObject("SELECT status FROM " + schema + ".orders WHERE id = ?", Integer.class, donePaidId))
                .isEqualTo(OrderStatus.DONE.ordinal());

        // 3. Run migration V20
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema(schema)
                .schemas(schema)
                .locations(locations)
                .resourceProvider(new MigrationResourceProvider(locations))
                .javaMigrationClassProvider(List::of)
                .javaMigrations(new V3__LegacyTranslations())
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .failOnMissingLocations(true)
                .ignoreMigrationPatterns(new String[0])
                .target("20")
                .load()
                .migrate();

        // 4. Verify after V20: DELIVERED + PAID became COMPLETE, others remained untouched
        txTemplate.executeWithoutResult(status -> {
            entityManager.clear();
            OrderEntity o1 = orderRepository.findById(deliveredPaidId).orElseThrow();
            OrderEntity o2 = orderRepository.findById(deliveredUnpaidId).orElseThrow();
            OrderEntity o3 = orderRepository.findById(acceptedPaidId).orElseThrow();
            OrderEntity o4 = orderRepository.findById(donePaidId).orElseThrow();

            // Assert via entity getters (validates enum ordinal mapping)
            assertThat(o1.getStatus()).as("Delivered + Paid becomes COMPLETE").isEqualTo(OrderStatus.COMPLETE);
            assertThat(o1.getPaymentStatus()).isEqualTo(OrderPaymentStatus.PAID);

            assertThat(o2.getStatus()).as("Delivered + Unpaid stays DELIVERED").isEqualTo(OrderStatus.DELIVERED);
            assertThat(o2.getPaymentStatus()).isEqualTo(OrderPaymentStatus.UNPAID);

            assertThat(o3.getStatus()).as("Accepted + Paid stays ACCEPTED").isEqualTo(OrderStatus.ACCEPTED);
            assertThat(o3.getPaymentStatus()).isEqualTo(OrderPaymentStatus.PAID);

            assertThat(o4.getStatus()).as("Done + Paid stays DONE").isEqualTo(OrderStatus.DONE);
            assertThat(o4.getPaymentStatus()).isEqualTo(OrderPaymentStatus.PAID);
        });

        // Also assert raw database integer values match expected ordinals
        assertThat(jdbc.queryForObject("SELECT status FROM " + schema + ".orders WHERE id = ?", Integer.class, deliveredPaidId))
                .isEqualTo(5); // COMPLETE
        assertThat(jdbc.queryForObject("SELECT status FROM " + schema + ".orders WHERE id = ?", Integer.class, deliveredUnpaidId))
                .isEqualTo(4); // DELIVERED
        assertThat(jdbc.queryForObject("SELECT status FROM " + schema + ".orders WHERE id = ?", Integer.class, acceptedPaidId))
                .isEqualTo(1); // ACCEPTED
        assertThat(jdbc.queryForObject("SELECT status FROM " + schema + ".orders WHERE id = ?", Integer.class, donePaidId))
                .isEqualTo(3); // DONE
    }

    private OrderEntity createEntity(int orderNumber, OrderStatus status, OrderPaymentStatus paymentStatus) {
        OrderEntity entity = new OrderEntity();
        entity.setOrderNumber(orderNumber);
        entity.setStatus(status);
        entity.setPaymentStatus(paymentStatus);
        entity.setCreatedAt(LocalDateTime.now().minusMinutes(30));
        entity.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
        entity.setTotal(new BigDecimal("15.50"));
        entity.setOrderChannel(OrderChannel.COUNTER);
        entity.setOrderLanguage("en");
        entity.setServiceType(ServiceType.TAKEAWAY);
        entity.setCustomerName("Diner " + orderNumber);
        return entity;
    }
}
