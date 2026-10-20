package es.brasatech.fastbite.service;

import es.brasatech.fastbite.domain.event.OrderPaymentStatusChangedEvent;
import es.brasatech.fastbite.domain.event.OrderStatusChangedEvent;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages Server-Sent Events (SSE) connections for live real-time order tracking:
 * 1. Restaurant dashboard/kitchen streams: subscribed to all updates for a tenant.
 * 2. Guest order confirmation streams: subscribed to a specific order ID.
 */
@Slf4j
@Service
public class OrderLiveEventService {

    private static final Long SSE_TIMEOUT = 50 * 1000L; // 50 seconds (clean close before Firebase 60s timeout)
    private static final Long SSE_RECONNECT_RETRY_MS = 2000L;

    // Tenant ID -> List of SSE emitters (staff dashboards / POS / KDS)
    private final Map<String, List<SseEmitter>> tenantEmitters = new ConcurrentHashMap<>();

    // Order ID -> List of SSE emitters (guests on order-confirmation page)
    private final Map<String, List<SseEmitter>> orderEmitters = new ConcurrentHashMap<>();

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed(ContextClosedEvent event) {
        closeAllEmitters();
    }

    /**
     * Closes all active SSE emitters so Tomcat graceful shutdown does not wait
     * for long-lived streaming connections to expire.
     */
    public void closeAllEmitters() {
        log.info("Closing all active SSE order tracking emitters for shutdown");
        for (List<SseEmitter> list : tenantEmitters.values()) {
            for (SseEmitter emitter : list) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
            list.clear();
        }
        tenantEmitters.clear();

        for (List<SseEmitter> list : orderEmitters.values()) {
            for (SseEmitter emitter : list) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
            list.clear();
        }
        orderEmitters.clear();
    }

    int activeTenantEmittersCount() {
        return tenantEmitters.values().stream().mapToInt(List::size).sum();
    }

    int activeOrderEmittersCount() {
        return orderEmitters.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Subscribes a staff dashboard to live order updates for a given restaurant.
     */
    public SseEmitter subscribeTenant(String tenantId) {
        String effectiveTenant = normalize(tenantId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        List<SseEmitter> list = tenantEmitters.computeIfAbsent(effectiveTenant, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> {
            list.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> list.remove(emitter));

        // Initial connection handshake event with retry hint
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .reconnectTime(SSE_RECONNECT_RETRY_MS)
                    .data(Map.of("tenantId", effectiveTenant)));
        } catch (IOException e) {
            list.remove(emitter);
        }

        return emitter;
    }

    /**
     * Subscribes a guest to live status updates of their specific order.
     */
    public SseEmitter subscribeOrder(String orderId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        List<SseEmitter> list = orderEmitters.computeIfAbsent(orderId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> {
            list.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> list.remove(emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .reconnectTime(SSE_RECONNECT_RETRY_MS)
                    .data(Map.of("orderId", orderId)));
        } catch (IOException e) {
            list.remove(emitter);
        }

        return emitter;
    }

    @EventListener
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        broadcast(event.order(), "status-changed");
    }

    @EventListener
    public void onOrderPaymentStatusChanged(OrderPaymentStatusChangedEvent event) {
        broadcast(event.order(), "payment-changed");
    }

    private void broadcast(Order order, String eventType) {
        if (order == null || order.id() == null) return;

        Map<String, Object> payload = Map.of(
                "id", order.id(),
                "orderNumber", order.orderNumber(),
                "status", order.status().name(),
                "paymentStatus", order.paymentStatus().name(),
                "eventType", eventType
        );

        // 1. Notify guests tracking this specific order
        List<SseEmitter> guestList = orderEmitters.get(order.id());
        if (guestList != null && !guestList.isEmpty()) {
            for (SseEmitter emitter : guestList) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("order-update")
                            .data(payload, MediaType.APPLICATION_JSON));
                } catch (Exception e) {
                    guestList.remove(emitter);
                }
            }
        }

        // 2. Notify staff dashboards in the current tenant
        String currentTenant = normalize(TenantContext.getCurrentTenant());
        List<SseEmitter> staffList = tenantEmitters.get(currentTenant);
        if (staffList != null && !staffList.isEmpty()) {
            for (SseEmitter emitter : staffList) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("order-update")
                            .data(payload, MediaType.APPLICATION_JSON));
                } catch (Exception e) {
                    staffList.remove(emitter);
                }
            }
        }
    }

    public void broadcastTableUpdated(String tableId) {
        String currentTenant = normalize(TenantContext.getCurrentTenant());
        List<SseEmitter> staffList = tenantEmitters.get(currentTenant);
        if (staffList != null && !staffList.isEmpty()) {
            Map<String, Object> payload = Map.of(
                    "tableId", tableId,
                    "eventType", "table-updated"
            );
            for (SseEmitter emitter : staffList) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("order-update")
                            .data(payload, MediaType.APPLICATION_JSON));
                } catch (Exception e) {
                    staffList.remove(emitter);
                }
            }
        }
    }

    private String normalize(String tenantId) {
        return (tenantId == null || tenantId.isBlank()) ? "default" : tenantId.trim().toLowerCase();
    }
}
