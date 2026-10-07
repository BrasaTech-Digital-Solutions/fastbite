package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.office.GroupService;
import es.brasatech.fastbite.application.office.I18nConfig;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.table.TableSignatureUtil;
import es.brasatech.fastbite.domain.group.Group;
import es.brasatech.fastbite.domain.order.CartItem;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.OrderStatus;
import es.brasatech.fastbite.domain.order.ServiceType;
import es.brasatech.fastbite.domain.product.ProductCustomizer;
import es.brasatech.fastbite.domain.product.ProductDto;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.discount.DiscountServiceJpaImpl;
import es.brasatech.fastbite.jpa.group.GroupServiceJpaImpl;
import es.brasatech.fastbite.jpa.order.OrderServiceJpaImpl;
import es.brasatech.fastbite.jpa.product.ProductServiceJpaImpl;
import es.brasatech.fastbite.jpa.table.TableServiceJpaImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The web layer reads what the services return after the database session is closed
 * (spring.jpa.open-in-view is off), so nothing returned may still depend on it.
 */
@SpringBootTest(classes = es.brasatech.fastbite.jpa.TestConfig.class)
@ActiveProfiles("jpa")
@Import({ProductServiceJpaImpl.class, GroupServiceJpaImpl.class, OrderServiceJpaImpl.class, TableServiceJpaImpl.class,
        DiscountServiceJpaImpl.class, TableSignatureUtil.class, ResultsOutliveTheirTransactionIntegrationTest.Beans.class})
class ResultsOutliveTheirTransactionIntegrationTest {

    @TestConfiguration
    static class Beans {
        @Bean
        I18nConfig i18nConfig() {
            return new I18nConfig();
        }
    }

    @Autowired
    private TenantProvisionerAdapter provisioner;
    @Autowired
    private ProductService products;
    @Autowired
    private GroupService groups;
    @Autowired
    private OrderService orders;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aProductsCustomizationsAndAGroupsProductsCanBeReadAfterTheCall() {
        provisioner.provisionTenant("detached");
        TenantContext.setCurrentTenant("detached");
        String productId = products.create(new ProductDto(null, "Kebab", BigDecimal.TEN, "Beef kebab", "/k.webp",
                Set.of("sauce", "toppings"), true)).id();
        String groupId = groups.create(new Group(null, "Food", "Main dishes", "fa-utensils", List.of(productId))).id();

        // No transaction around these calls, as in a controller
        assertThat(products.findById(productId).orElseThrow().customizations()).containsExactlyInAnyOrder("sauce", "toppings");
        assertThat(products.findAll()).singleElement()
                .satisfies(product -> assertThat(product.customizations()).hasSize(2));
        assertThat(groups.findById(groupId).orElseThrow().products()).containsExactly(productId);
        assertThat(groups.findAll()).singleElement()
                .satisfies(group -> assertThat(group.products()).containsExactly(productId));
    }

    @Test
    void anOrderCanBePaidAndMovedAlongWithoutATransactionAroundTheCall() {
        provisioner.provisionTenant("detachedorders");
        TenantContext.setCurrentTenant("detachedorders");
        List<CartItem> items = List.of(new CartItem(null, "kebab", "Kebab", "Beef kebab", "/k.webp", 2,
                List.of(new ProductCustomizer("sauce-opt-0", "Garlic", BigDecimal.ZERO, 1)), new BigDecimal("6.50")));
        String orderId = orders.createOrder(items, 1, OrderPaymentStatus.UNPAID, OrderChannel.COUNTER, "en", "cashier",
                "Marta", ServiceType.TAKEAWAY).id();

        // These read the order and save it again; as in a controller, nothing wraps them in a transaction
        orders.markOrderPaid(orderId);
        orders.moveToNextStatus(orderId);

        Order saved = orders.findById(orderId).orElseThrow();
        assertThat(saved.paymentStatus()).isEqualTo(OrderPaymentStatus.PAID);
        assertThat(saved.status()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(saved.serviceType()).isEqualTo(ServiceType.TAKEAWAY);
        assertThat(saved.items()).singleElement().satisfies(item ->
                assertThat(item.customizations()).extracting(ProductCustomizer::name).containsExactly("Garlic"));
        assertThat(orders.getAllOrder()).singleElement()
                .satisfies(order -> assertThat(order.total()).isEqualByComparingTo("13.00"));
    }

    @Test
    void anOnlineTakeawayOrderReachesStaffOnlyOncePaidAndIsNeverPaidAtPickup() {
        provisioner.provisionTenant("prepaid");
        TenantContext.setCurrentTenant("prepaid");
        List<CartItem> items = List.of(new CartItem(null, "kebab", "Kebab", "Beef kebab", "/k.webp", 1, List.of(),
                new BigDecimal("6.50")));
        String atPickup = orders.createOrder(items, 1, OrderPaymentStatus.UNPAID, OrderChannel.COUNTER, "en", "cashier",
                "Pedro", ServiceType.TAKEAWAY).id();
        String online = orders.createOrder(items, 2, OrderPaymentStatus.UNPAID, OrderChannel.ONLINE, "en", null,
                "Marta", ServiceType.TAKEAWAY).id();

        assertThat(orders.getAllOrder()).extracting(Order::id).containsExactly(atPickup);
        assertThat(orders.findTakeawayAwaitingPayment()).extracting(Order::id).containsExactly(atPickup);

        orders.markOrderPaid(online);

        assertThat(orders.getAllOrder()).extracting(Order::id).containsExactlyInAnyOrder(atPickup, online);
        assertThat(orders.findTakeawayAwaitingPayment()).extracting(Order::id).containsExactly(atPickup);
        assertThat(orders.findById(online).orElseThrow().status()).isEqualTo(OrderStatus.ACCEPTED);
    }
}
