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
}
