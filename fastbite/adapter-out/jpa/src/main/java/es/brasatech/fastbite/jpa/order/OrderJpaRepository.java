package es.brasatech.fastbite.jpa.order;

import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.OrderStatus;
import es.brasatech.fastbite.domain.order.ServiceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface OrderJpaRepository extends JpaRepository<OrderEntity, String> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from Order o where o.id = :id")
    java.util.Optional<OrderEntity> findForUpdate(@org.springframework.data.repository.query.Param("id") String id);

    @org.springframework.data.jpa.repository.Query("""
            select o.id from Order o where o.serviceType = es.brasatech.fastbite.domain.order.ServiceType.TAKEAWAY
            and o.orderChannel = es.brasatech.fastbite.domain.order.OrderChannel.ONLINE
            and o.paymentStatus = es.brasatech.fastbite.domain.order.OrderPaymentStatus.UNPAID
            and o.status = es.brasatech.fastbite.domain.order.OrderStatus.CREATED and o.createdAt <= :cutoff
            order by o.id
            """)
    List<String> findExpiredPaymentIds(@org.springframework.data.repository.query.Param("cutoff") java.time.LocalDateTime cutoff);

    List<OrderEntity> findByServiceTypeAndPaymentStatusAndStatusNotInOrderByCreatedAt(ServiceType serviceType,
            OrderPaymentStatus paymentStatus, Collection<OrderStatus> closed);

    @org.springframework.data.jpa.repository.Query("""
            select count(o) from DiningTable t join t.orderIds oid, Order o
            where t.id = :tableId and o.id = oid
            and o.orderChannel = es.brasatech.fastbite.domain.order.OrderChannel.TABLE
            and o.createdAt >= :since
            """)
    long countRecentTableQrOrders(@org.springframework.data.repository.query.Param("tableId") String tableId,
            @org.springframework.data.repository.query.Param("since") java.time.LocalDateTime since);
}
