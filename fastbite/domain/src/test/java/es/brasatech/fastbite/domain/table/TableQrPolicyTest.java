package es.brasatech.fastbite.domain.table;

import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TableQrPolicyTest {

    private Table tableWithOverride(Boolean override) {
        return new Table("tbl-1", "Table 1", 4, TableStatus.AVAILABLE, true, List.of(), override, "staff-1", LocalDateTime.now());
    }

    private Table tableWithoutOverride() {
        return new Table("tbl-1", "Table 1", 4, TableStatus.AVAILABLE, true, List.of(), null, null, null);
    }

    @Test
    void defaultSupervised_withoutOverride_routesToCreatedRegardlessOfRecentCount() {
        Table table = tableWithoutOverride();
        // under threshold
        var d1 = TableQrPolicy.initialStatus(table, false, 0);
        assertThat(d1.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(d1.approvalNote()).isNull();

        var d2 = TableQrPolicy.initialStatus(table, false, 2);
        assertThat(d2.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(d2.approvalNote()).isNull();

        // at threshold
        var d3 = TableQrPolicy.initialStatus(table, false, 3);
        assertThat(d3.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(d3.approvalNote()).isNull();

        // over threshold
        var d4 = TableQrPolicy.initialStatus(table, false, 5);
        assertThat(d4.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(d4.approvalNote()).isNull();
    }

    @Test
    void defaultTrusted_withoutOverride_routesToAcceptedUnderThreshold() {
        Table table = tableWithoutOverride();
        var d0 = TableQrPolicy.initialStatus(table, true, 0);
        assertThat(d0.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(d0.approvalNote()).isNull();

        var d2 = TableQrPolicy.initialStatus(table, true, 2);
        assertThat(d2.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(d2.approvalNote()).isNull();
    }

    @Test
    void defaultTrusted_withoutOverride_tripsBrakeAtAndOverThreshold() {
        Table table = tableWithoutOverride();
        // at threshold (3)
        var at = TableQrPolicy.initialStatus(table, true, 3);
        assertThat(at.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(at.approvalNote()).isEqualTo(Order.NEEDS_APPROVAL_TOO_MANY_ORDERS);

        // over threshold (4)
        var over = TableQrPolicy.initialStatus(table, true, 4);
        assertThat(over.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(over.approvalNote()).isEqualTo(Order.NEEDS_APPROVAL_TOO_MANY_ORDERS);
    }

    @Test
    void defaultSupervised_withTrustedOverride_routesLikeTrustedTable() {
        Table table = tableWithOverride(true);
        // under threshold
        var under = TableQrPolicy.initialStatus(table, false, 1);
        assertThat(under.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(under.approvalNote()).isNull();

        // at threshold
        var at = TableQrPolicy.initialStatus(table, false, 3);
        assertThat(at.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(at.approvalNote()).isEqualTo(Order.NEEDS_APPROVAL_TOO_MANY_ORDERS);

        // over threshold
        var over = TableQrPolicy.initialStatus(table, false, 5);
        assertThat(over.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(over.approvalNote()).isEqualTo(Order.NEEDS_APPROVAL_TOO_MANY_ORDERS);
    }

    @Test
    void defaultTrusted_withSupervisedOverride_routesToCreatedRegardlessOfRecentCount() {
        Table table = tableWithOverride(false);
        var d0 = TableQrPolicy.initialStatus(table, true, 0);
        assertThat(d0.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(d0.approvalNote()).isNull();

        var d3 = TableQrPolicy.initialStatus(table, true, 3);
        assertThat(d3.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(d3.approvalNote()).isNull();
    }

    @Test
    void customThreshold_respectsCustomValue() {
        Table table = tableWithOverride(true);
        int customThreshold = 5;

        var under = TableQrPolicy.initialStatus(table, false, 4, customThreshold);
        assertThat(under.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(under.approvalNote()).isNull();

        var at = TableQrPolicy.initialStatus(table, false, 5, customThreshold);
        assertThat(at.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(at.approvalNote()).isEqualTo(Order.NEEDS_APPROVAL_TOO_MANY_ORDERS);

        var over = TableQrPolicy.initialStatus(table, false, 6, customThreshold);
        assertThat(over.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(over.approvalNote()).isEqualTo(Order.NEEDS_APPROVAL_TOO_MANY_ORDERS);
    }

    @Test
    void nullTable_fallsBackToRestaurantDefault() {
        var supervised = TableQrPolicy.initialStatus(null, false, 0);
        assertThat(supervised.status()).isEqualTo(OrderStatus.CREATED);

        var trusted = TableQrPolicy.initialStatus(null, true, 0);
        assertThat(trusted.status()).isEqualTo(OrderStatus.ACCEPTED);

        var tripped = TableQrPolicy.initialStatus(null, true, 3);
        assertThat(tripped.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(tripped.approvalNote()).isEqualTo(Order.NEEDS_APPROVAL_TOO_MANY_ORDERS);
    }

    @Test
    void orderSetStatus_clearsCancelReasonOnAccept() {
        Order order = new Order(
                List.of(),
                1,
                OrderStatus.CREATED,
                Order.NEEDS_APPROVAL_TOO_MANY_ORDERS,
                es.brasatech.fastbite.domain.order.OrderPaymentStatus.UNPAID,
                es.brasatech.fastbite.domain.order.OrderChannel.TABLE,
                "en",
                null,
                "Table 1",
                es.brasatech.fastbite.domain.order.ServiceType.DINE_IN
        );
        assertThat(order.cancelReason()).isEqualTo(Order.NEEDS_APPROVAL_TOO_MANY_ORDERS);

        Order accepted = order.setStatus(OrderStatus.ACCEPTED);
        assertThat(accepted.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(accepted.cancelReason()).isNull();
    }
}
