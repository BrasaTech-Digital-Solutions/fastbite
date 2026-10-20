package es.brasatech.fastbite.jpa.table;

import es.brasatech.fastbite.application.table.TableSignatureUtil;
import es.brasatech.fastbite.domain.table.Table;
import es.brasatech.fastbite.jpa.order.OrderJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Map;

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
}
