package es.brasatech.fastbite.application.table;

import es.brasatech.fastbite.domain.table.Table;

import java.util.List;
import java.util.Optional;

public interface TableService {
    List<Table> findAll();

    Optional<Table> findById(String id);

    Table create(Table table);

    Optional<Table> update(String id, Table table);

    boolean delete(String id);

    void assignOrder(String tableId, String orderId);

    void unassignOrder(String tableId, String orderId);

    void resetTableSessionIfAllPaid(String tableId);

    Optional<Table> findTableByOrderId(String orderId);

    java.util.Map<String, Table> findTablesByOrderIds(java.util.Collection<String> orderIds);

    Optional<Table> findTableByNameOrId(String tableParam);

    String validateAndBindTableSession(String tableParam, String tokenParam, String existingTableNumber, List<?> cartItems);
}

