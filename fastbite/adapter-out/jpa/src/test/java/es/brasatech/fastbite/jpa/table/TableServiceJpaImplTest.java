package es.brasatech.fastbite.jpa.table;

import es.brasatech.fastbite.application.table.TableSignatureUtil;
import es.brasatech.fastbite.domain.table.Table;
import es.brasatech.fastbite.domain.table.TableStatus;
import es.brasatech.fastbite.jpa.order.OrderJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TableServiceJpaImplTest {

    @Mock
    private TableJpaRepository repository;
    @Mock
    private OrderJpaRepository orderRepository;
    @Mock
    private TableSignatureUtil tableSignatureUtil;

    private TableServiceJpaImpl service;

    @BeforeEach
    void setUp() {
        service = new TableServiceJpaImpl(repository, orderRepository, tableSignatureUtil);
    }

    @Test
    void findTablesByOrderIds_whenEmptyCollection_returnsEmptyWithoutQueryingRepository() {
        Map<String, Table> result = service.findTablesByOrderIds(List.of());
        assertThat(result).isEmpty();
        verify(repository, never()).findTableOrderPairs(any());
    }

    @Test
    void findTablesByOrderIds_mapsPairsCorrectly() {
        List<Object[]> rows = List.of(
                new Object[]{"tbl-1", "Table 1", "ord-1"},
                new Object[]{"tbl-1", "Table 1", "ord-2"},
                new Object[]{"tbl-2", "Table 2", "ord-3"}
        );
        when(repository.findTableOrderPairs(List.of("ord-1", "ord-2", "ord-3"))).thenReturn(rows);

        Map<String, Table> result = service.findTablesByOrderIds(List.of("ord-1", "ord-2", "ord-3"));

        assertThat(result).hasSize(3);
        assertThat(result.get("ord-1").id()).isEqualTo("tbl-1");
        assertThat(result.get("ord-1").name()).isEqualTo("Table 1");
        assertThat(result.get("ord-2").id()).isEqualTo("tbl-1");
        assertThat(result.get("ord-2").name()).isEqualTo("Table 1");
        assertThat(result.get("ord-3").id()).isEqualTo("tbl-2");
        assertThat(result.get("ord-3").name()).isEqualTo("Table 2");
    }

    @Test
    void setQrTrustedOverride_setsTrustedAndAuditFields() {
        TableEntity entity = new TableEntity();
        entity.setId("tbl-1");
        entity.setName("Table 1");
        when(repository.findById("tbl-1")).thenReturn(java.util.Optional.of(entity));
        when(repository.save(any(TableEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.setQrTrustedOverride("tbl-1", true, "staff-user-42");

        assertThat(result).isPresent();
        assertThat(result.get().qrTrusted()).isTrue();
        assertThat(result.get().qrTrustedBy()).isEqualTo("staff-user-42");
        assertThat(result.get().qrTrustedAt()).isNotNull();
    }

    @Test
    void setQrTrustedOverride_whenNull_clearsTrustedAndAuditFields() {
        TableEntity entity = new TableEntity();
        entity.setId("tbl-1");
        entity.setName("Table 1");
        entity.setQrTrusted(true);
        entity.setQrTrustedBy("staff-user-42");
        entity.setQrTrustedAt(java.time.LocalDateTime.now());
        when(repository.findById("tbl-1")).thenReturn(java.util.Optional.of(entity));
        when(repository.save(any(TableEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.setQrTrustedOverride("tbl-1", null, "staff-user-42");

        assertThat(result).isPresent();
        assertThat(result.get().qrTrusted()).isNull();
        assertThat(result.get().qrTrustedBy()).isNull();
        assertThat(result.get().qrTrustedAt()).isNull();
    }

    @Test
    void resetTableSessionIfAllPaid_resetsQrTrustedOverridesWhenAllPaid() {
        TableEntity entity = new TableEntity();
        entity.setId("tbl-1");
        entity.setName("Table 1");
        entity.setStatus(es.brasatech.fastbite.domain.table.TableStatus.OCCUPIED);
        entity.setOrderIds(new java.util.ArrayList<>(List.of("ord-1")));
        entity.setQrTrusted(true);
        entity.setQrTrustedBy("staff-1");
        entity.setQrTrustedAt(java.time.LocalDateTime.now());

        es.brasatech.fastbite.jpa.order.OrderEntity paidOrder = new es.brasatech.fastbite.jpa.order.OrderEntity();
        paidOrder.setId("ord-1");
        paidOrder.setPaymentStatus(es.brasatech.fastbite.domain.order.OrderPaymentStatus.PAID);
        paidOrder.setStatus(es.brasatech.fastbite.domain.order.OrderStatus.COMPLETE);

        when(repository.findById("tbl-1")).thenReturn(java.util.Optional.of(entity));
        when(orderRepository.findById("ord-1")).thenReturn(java.util.Optional.of(paidOrder));

        service.resetTableSessionIfAllPaid("tbl-1");

        assertThat(entity.getOrderIds()).isEmpty();
        assertThat(entity.getStatus()).isEqualTo(es.brasatech.fastbite.domain.table.TableStatus.AVAILABLE);
        assertThat(entity.getQrTrusted()).isNull();
        assertThat(entity.getQrTrustedBy()).isNull();
        assertThat(entity.getQrTrustedAt()).isNull();
        verify(repository).save(entity);
    }

    @Test
    void update_whenOrderIdsIsNull_keepsExistingOpenOrdersAttached() {
        TableEntity entity = new TableEntity();
        entity.setId("tbl-1");
        entity.setName("Table 1");
        entity.setSeats(4);
        entity.setStatus(TableStatus.OCCUPIED);
        entity.setActive(true);
        entity.setOrderIds(new ArrayList<>(List.of("ord-1", "ord-2")));

        when(repository.findById("tbl-1")).thenReturn(Optional.of(entity));
        when(repository.save(any(TableEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<Table> result = service.update("tbl-1",
                new Table("tbl-1", "Table 1 Renamed", 6, TableStatus.OCCUPIED, true, null));

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Table 1 Renamed");
        assertThat(result.get().seats()).isEqualTo(6);
        assertThat(result.get().orderIds()).containsExactly("ord-1", "ord-2");
        assertThat(entity.getOrderIds()).containsExactly("ord-1", "ord-2");
        verify(repository).save(entity);
    }

    @Test
    void update_whenOrderIdsIsNullOnEmptyTable_succeedsWithEmptyOrders() {
        TableEntity entity = new TableEntity();
        entity.setId("tbl-2");
        entity.setName("Table 2");
        entity.setSeats(4);
        entity.setStatus(TableStatus.AVAILABLE);
        entity.setActive(true);

        when(repository.findById("tbl-2")).thenReturn(Optional.of(entity));
        when(repository.save(any(TableEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<Table> result = service.update("tbl-2",
                new Table("tbl-2", "Table 2", 2, TableStatus.AVAILABLE, false, null));

        assertThat(result).isPresent();
        assertThat(result.get().seats()).isEqualTo(2);
        assertThat(result.get().active()).isFalse();
        assertThat(result.get().orderIds()).isEmpty();
        verify(repository).save(entity);
    }

    @Test
    void update_whenOrderIdsProvided_replacesOrderIds() {
        TableEntity entity = new TableEntity();
        entity.setId("tbl-3");
        entity.setName("Table 3");
        entity.setSeats(4);
        entity.setStatus(TableStatus.OCCUPIED);
        entity.setActive(true);
        entity.setOrderIds(new ArrayList<>(List.of("ord-1")));

        when(repository.findById("tbl-3")).thenReturn(Optional.of(entity));
        when(repository.save(any(TableEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<Table> result = service.update("tbl-3",
                new Table("tbl-3", "Table 3", 4, TableStatus.OCCUPIED, true, List.of("ord-2", "ord-3")));

        assertThat(result).isPresent();
        assertThat(result.get().orderIds()).containsExactly("ord-2", "ord-3");
        assertThat(entity.getOrderIds()).containsExactly("ord-2", "ord-3");
        verify(repository).save(entity);
    }
}
