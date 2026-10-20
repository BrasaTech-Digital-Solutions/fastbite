package es.brasatech.fastbite.jpa.table;

import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.domain.table.Table;
import es.brasatech.fastbite.domain.table.TableStatus;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class TableServiceJpaImpl implements TableService {
    private final TableJpaRepository repository;
    private final es.brasatech.fastbite.jpa.order.OrderJpaRepository orderRepository;
    private final es.brasatech.fastbite.application.table.TableSignatureUtil tableSignatureUtil;

    @Override
    public List<Table> findAll() {
        return repository.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<Table> findById(String id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public Table create(Table table) {
        TableEntity entity = new TableEntity();
        if (table.id() != null && !table.id().isEmpty()) {
            entity.setId(table.id());
        }
        entity.setName(table.name());
        entity.setSeats(table.seats());
        entity.setStatus(table.status());
        entity.setActive(table.active());
        entity.setQrTrusted(table.qrTrusted());
        entity.setQrTrustedBy(table.qrTrustedBy());
        entity.setQrTrustedAt(table.qrTrustedAt());
        return toDomain(repository.save(entity));
    }

    @Override
    public Optional<Table> update(String id, Table table) {
        return repository.findById(id).map(entity -> {
            entity.setName(table.name());
            entity.setSeats(table.seats());
            entity.setStatus(table.status());
            entity.setActive(table.active());
            entity.setOrderIds(new java.util.ArrayList<>(table.orderIds()));
            entity.setQrTrusted(table.qrTrusted());
            entity.setQrTrustedBy(table.qrTrustedBy());
            entity.setQrTrustedAt(table.qrTrustedAt());
            repository.save(entity);
            return toDomain(entity);
        });
    }

    @Override
    public boolean delete(String id) {
        if (repository.existsById(id)) {
            repository.deleteById(id);
            return true;
        }
        return false;
    }

    @Override
    public void assignOrder(String tableId, String orderId) {
        repository.findById(tableId).ifPresent(entity -> {
            if (!entity.getOrderIds().contains(orderId)) {
                entity.getOrderIds().add(orderId);
                entity.setStatus(TableStatus.OCCUPIED);
                repository.save(entity);
            }
        });
    }

    @Override
    public void unassignOrder(String tableId, String orderId) {
        repository.findById(tableId).ifPresent(entity -> {
            entity.getOrderIds().remove(orderId);
            if (entity.getOrderIds().isEmpty()) {
                entity.setStatus(TableStatus.AVAILABLE);
            }
            repository.save(entity);
            resetTableSessionIfAllPaid(tableId);
        });
    }

    @Override
    public void resetTableSessionIfAllPaid(String tableId) {
        repository.findById(tableId).ifPresent(entity -> {
            boolean allCleared = entity.getOrderIds().stream()
                    .map(orderId -> orderRepository.findById(orderId))
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::get)
                    .allMatch(order -> {
                        boolean isPaid = order
                                .getPaymentStatus() == es.brasatech.fastbite.domain.order.OrderPaymentStatus.PAID;
                        boolean isCancelled = order
                                .getStatus() == es.brasatech.fastbite.domain.order.OrderStatus.CANCELLED;
                        return isPaid || isCancelled;
                    });

            if (allCleared && !entity.getOrderIds().isEmpty()) {
                entity.setOrderIds(new java.util.ArrayList<>());
                entity.setStatus(TableStatus.AVAILABLE);
                entity.setQrTrusted(null);
                entity.setQrTrustedBy(null);
                entity.setQrTrustedAt(null);
                repository.save(entity);
            }
        });
    }

    @Override
    public Optional<Table> findTableByOrderId(String orderId) {
        return repository.findAll().stream()
                .filter(table -> table.getOrderIds().contains(orderId))
                .findFirst()
                .map(this::toDomain);
    }

    @Override
    public java.util.Map<String, Table> findTablesByOrderIds(java.util.Collection<String> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return java.util.Map.of();
        }
        List<Object[]> rows = repository.findTableOrderPairs(orderIds);
        java.util.Map<String, Table> result = new java.util.HashMap<>();
        for (Object[] row : rows) {
            String tableId = (String) row[0];
            String tableName = (String) row[1];
            String orderId = (String) row[2];
            result.put(orderId, new Table(tableId, tableName, 0, TableStatus.AVAILABLE, true, List.of()));
        }
        return result;
    }

    @Override
    public Optional<Table> findTableByNameOrId(String tableParam) {
        if (tableParam == null || tableParam.trim().isEmpty()) {
            return Optional.empty();
        }
        var opt = findById(tableParam);
        if (opt.isPresent()) {
            return opt;
        }
        var tables = findAll();
        for (var t : tables) {
            if (t.name().equalsIgnoreCase(tableParam) || t.name().equalsIgnoreCase("Table " + tableParam) || t.id().equals(tableParam)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    @Override
    public String validateAndBindTableSession(String tableParam, String tokenParam, String existingTableNumber, List<?> cartItems) {
        if (tableParam == null || tableParam.trim().isEmpty()) {
            return null;
        }
        var opt = findTableByNameOrId(tableParam);
        if (opt.isPresent()) {
            var foundTable = opt.get();
            // Cryptographic validation of signed URL parameter
            if (!tableSignatureUtil.isValid(TenantContext.getCurrentTenant(), foundTable.id(), tokenParam)) {
                throw new IllegalArgumentException("Invalid or missing secure table token. Scan the QR code at your table.");
            }

            // Session boundary protection: Active unpaid order on Table A cannot switch to Table B
            if (existingTableNumber != null && !existingTableNumber.equals(foundTable.id())) {
                if (cartItems != null && !cartItems.isEmpty()) {
                    throw new IllegalStateException("You have an active ordering session on another table. Please complete or clear it first.");
                }
            }
            return foundTable.id();
        }
        return null;
    }

    @Override
    public Optional<Table> setQrTrustedOverride(String tableId, Boolean trusted, String staffUserId) {
        return repository.findById(tableId).map(entity -> {
            entity.setQrTrusted(trusted);
            if (trusted != null) {
                entity.setQrTrustedBy(staffUserId);
                entity.setQrTrustedAt(java.time.LocalDateTime.now());
            } else {
                entity.setQrTrustedBy(null);
                entity.setQrTrustedAt(null);
            }
            repository.save(entity);
            return toDomain(entity);
        });
    }

    private Table toDomain(TableEntity entity) {
        return new Table(
                entity.getId(),
                entity.getName(),
                entity.getSeats(),
                entity.getStatus(),
                entity.isActive(),
                new java.util.ArrayList<>(entity.getOrderIds()),
                entity.getQrTrusted(),
                entity.getQrTrustedBy(),
                entity.getQrTrustedAt());
    }
}
