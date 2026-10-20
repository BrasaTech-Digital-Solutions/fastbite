package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.table.TableSignatureUtil;
import es.brasatech.fastbite.domain.order.*;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.order.OrderServiceJpaImpl;
import es.brasatech.fastbite.jpa.table.TableServiceJpaImpl;
import es.brasatech.fastbite.jpa.discount.DiscountServiceJpaImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = es.brasatech.fastbite.jpa.TestConfig.class)
@Import({OrderServiceJpaImpl.class, TableServiceJpaImpl.class, DiscountServiceJpaImpl.class,
        TableSignatureUtil.class, ResultsOutliveTheirTransactionIntegrationTest.Beans.class})
class AbandonedOrderIntegrationTest {

    @Autowired OrderService orders;
    @Autowired TenantProvisionerAdapter provisioner;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void expiresOnlyAbandonedOnlineTakeawayInTheSelectedTenant() {
        tenant("expiryother");
        String other = create(120, OrderChannel.ONLINE, ServiceType.TAKEAWAY, OrderPaymentStatus.UNPAID);
        tenant("expiryselection");
        String expired = create(120, OrderChannel.ONLINE, ServiceType.TAKEAWAY, OrderPaymentStatus.UNPAID);
        String recent = create(10, OrderChannel.ONLINE, ServiceType.TAKEAWAY, OrderPaymentStatus.UNPAID);
        String counter = create(120, OrderChannel.COUNTER, ServiceType.TAKEAWAY, OrderPaymentStatus.UNPAID);
        String table = create(120, OrderChannel.TABLE, ServiceType.DINE_IN, OrderPaymentStatus.UNPAID);
        String paid = create(120, OrderChannel.ONLINE, ServiceType.TAKEAWAY, OrderPaymentStatus.PAID);
        String accepted = create(10, OrderChannel.ONLINE, ServiceType.TAKEAWAY, OrderPaymentStatus.UNPAID);
        orders.setOrderStatus(accepted, OrderStatus.ACCEPTED);
        jdbc.update("update tenant_expiryselection.orders set created_at = ? where id = ?", LocalDateTime.now().minusHours(2), accepted);

        var all = orders.findAll();
        assertThat(all).filteredOn(o -> o.status() == OrderStatus.CANCELLED).extracting(Order::id).containsExactly(expired);
        assertThat(all).filteredOn(o -> o.status() == OrderStatus.CREATED).extracting(Order::id)
                .containsExactlyInAnyOrder(recent, counter, table, paid);
        assertThat(orders.findById(expired).orElseThrow().cancelReason()).isEqualTo(Order.PAYMENT_EXPIRED_REASON);
        assertThat(jdbc.queryForObject("select status from tenant_expiryother.orders where id = ?", Integer.class, other))
                .isEqualTo(OrderStatus.CREATED.ordinal());
        assertThat(orders.findTakeawayAwaitingPayment()).extracting(Order::id).containsExactly(counter);
    }

    @Test void markOrderPaidOnDeliveredOrderCompletesItAndOnAcceptedOrderKeepsItAccepted() {
        tenant("paycompletion");
        String deliveredId = create(10, OrderChannel.COUNTER, ServiceType.TAKEAWAY, OrderPaymentStatus.UNPAID);
        orders.setOrderStatus(deliveredId, OrderStatus.DELIVERED);
        orders.markOrderPaid(deliveredId);
        var completed = orders.findById(deliveredId).orElseThrow();
        assertThat(completed.status()).isEqualTo(OrderStatus.COMPLETE);
        assertThat(completed.paymentStatus()).isEqualTo(OrderPaymentStatus.PAID);

        String acceptedId = create(10, OrderChannel.COUNTER, ServiceType.TAKEAWAY, OrderPaymentStatus.UNPAID);
        orders.setOrderStatus(acceptedId, OrderStatus.ACCEPTED);
        orders.markOrderPaid(acceptedId);
        var accepted = orders.findById(acceptedId).orElseThrow();
        assertThat(accepted.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(accepted.paymentStatus()).isEqualTo(OrderPaymentStatus.PAID);
    }

    @Test void delayedPaymentRestoresExpiredOrderWithoutChangingItsPriceOrLines() {
        tenant("expirylate");
        String id = create(120, OrderChannel.ONLINE, ServiceType.TAKEAWAY, OrderPaymentStatus.UNPAID);
        var expired = orders.findById(id).orElseThrow();
        assertThat(expired.status()).isEqualTo(OrderStatus.CANCELLED);
        orders.markOrderPaid(id);
        orders.markOrderPaid(id);
        var paid = orders.findById(id).orElseThrow();
        assertThat(paid.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(paid.paymentStatus()).isEqualTo(OrderPaymentStatus.PAID);
        assertThat(paid.cancelReason()).isNull();
        assertThat(paid.items()).isEqualTo(expired.items());
        assertThat(paid.total()).isEqualByComparingTo(expired.total());
        assertThat(orders.getAllOrder()).extracting(Order::id).containsExactly(id);
    }

    @Test void manualCancellationIsNotReopenedByPayment() {
        tenant("expirymanual");
        String id = create(10, OrderChannel.ONLINE, ServiceType.TAKEAWAY, OrderPaymentStatus.UNPAID);
        orders.cancelOrder(id, "Restaurant closed");
        orders.markOrderPaid(id);
        var order = orders.findById(id).orElseThrow();
        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.cancelReason()).isEqualTo("Restaurant closed");
        assertThat(order.paymentStatus()).isEqualTo(OrderPaymentStatus.PAID);
    }

    @Test void paymentAndExpirySerializeInEitherOrder() throws Exception {
        tenant("expiryrace");
        for (boolean paymentFirst : List.of(false, true)) {
            String id = create(120, OrderChannel.ONLINE, ServiceType.TAKEAWAY, OrderPaymentStatus.UNPAID);
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> {
                    TenantContext.setCurrentTenant("expiryrace");
                    try {
                        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                            if (paymentFirst) orders.markOrderPaid(id); else orders.findById(id);
                            locked.countDown();
                            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out"); }
                            catch (InterruptedException e) { throw new IllegalStateException(e); }
                        });
                    } finally { TenantContext.clear(); }
                });
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> {
                    TenantContext.setCurrentTenant("expiryrace");
                    try {
                        started.countDown();
                        if (paymentFirst) orders.findAll(); else orders.markOrderPaid(id);
                    } finally { TenantContext.clear(); }
                });
                try {
                    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } finally { release.countDown(); }
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            }
            var paid = orders.findById(id).orElseThrow();
            assertThat(paid.paymentStatus()).isEqualTo(OrderPaymentStatus.PAID);
            assertThat(paid.status()).isEqualTo(OrderStatus.ACCEPTED);
        }
    }

    @Test void exactHourIsTheExpiryBoundary() {
        var now = LocalDateTime.now();
        var order = new Order(List.of(), 1, "boundary", now.minusHours(1), now, OrderStatus.CREATED,
                BigDecimal.TEN, null, OrderPaymentStatus.UNPAID, OrderChannel.ONLINE, "en", null, "Guest", ServiceType.TAKEAWAY);
        assertThat(order.paymentExpired(now.minusNanos(1))).isFalse();
        assertThat(order.paymentExpired(now)).isTrue();
    }

    private void tenant(String id) {
        provisioner.provisionTenant(id);
        TenantContext.setCurrentTenant(id);
    }

    private String create(int ageMinutes, OrderChannel channel, ServiceType service, OrderPaymentStatus payment) {
        var now = LocalDateTime.now();
        return orders.create(new Order(List.of(new CartItem(null, "meal", "Meal", "", "", 1, List.of(), BigDecimal.TEN)),
                1, null, now.minusMinutes(ageMinutes), now, OrderStatus.CREATED, BigDecimal.TEN, null, payment,
                channel, "en", null, "Guest", service)).id();
    }
}
