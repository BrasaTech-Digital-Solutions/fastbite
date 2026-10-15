package es.brasatech.fastbite.jpa.order;

import es.brasatech.fastbite.application.order.OrderNumberService;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Order numbers kept in the order_counter row of each restaurant's schema, so they survive
 * restarts and scale-out. The UPDATE locks the row: concurrent orders never get the same number.
 */
@Service
public class OrderNumberServiceJpaImpl implements OrderNumberService {

    private final EntityManager entityManager;
    private final Clock clock;

    @Autowired
    public OrderNumberServiceJpaImpl(EntityManager entityManager,
                                     @Value("${fastbite.orders.time-zone:Europe/Madrid}") ZoneId timeZone) {
        this(entityManager, Clock.system(timeZone));
    }

    OrderNumberServiceJpaImpl(EntityManager entityManager, Clock clock) {
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int next() {
        LocalDate today = LocalDate.now(clock);
        int updated = entityManager.createNativeQuery("""
                        UPDATE order_counter
                        SET last_number = CASE WHEN business_day = :today THEN last_number + 1 ELSE 1 END,
                            business_day = :today
                        WHERE id = 1""")
                .setParameter("today", today)
                .executeUpdate();
        if (updated == 0) {
            // schema.sql seeds the row; this only covers a schema it has not run on yet
            entityManager.createNativeQuery("INSERT INTO order_counter (id, business_day, last_number) VALUES (1, :today, 1)")
                    .setParameter("today", today)
                    .executeUpdate();
            return 1;
        }
        return ((Number) entityManager.createNativeQuery("SELECT last_number FROM order_counter WHERE id = 1")
                .getSingleResult()).intValue();
    }
}
