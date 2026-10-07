package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.order.OrderNumberService;
import es.brasatech.fastbite.application.order.OrderPricingService;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.domain.order.CartItem;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.ServiceType;
import es.brasatech.fastbite.domain.table.Table;
import es.brasatech.fastbite.dto.order.OrderCancelReason;
import es.brasatech.fastbite.dto.order.OrderStatusChange;
import es.brasatech.fastbite.service.OrderCheckoutService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import es.brasatech.fastbite.application.settings.RestaurantSettingsService;

@Controller
@RequiredArgsConstructor
public class OrderController {

    private static final String SESSION_ORDER_ID = "orderId";

    private final MessageSource messageSource;
    private final OrderNumberService orderNumberService;
    private final OrderPricingService orderPricingService;
    private final OrderService orderService;
    private final TableService tableService;
    private final RestaurantSettingsService settingsService;
    private final OrderCheckoutService orderCheckoutService;
    private final es.brasatech.fastbite.service.OrderLiveEventService orderLiveEventService;

    public record CreateOrderRequest(
        List<CartItem> items,
        String customerName,
        String paymentMethod
    ) {}

    /**
     * How a guest is served is not theirs to choose: a guest whose session a table's QR code bound
     * to that table orders for it; everyone else orders takeaway, paid online first.
     */
    @ResponseBody
    @es.brasatech.fastbite.config.RequiresOrderingSubscription
    @PostMapping({"/{tenantId}/api/create-order", "/api/create-order"})
    public Map<String, Object> postOrder(@RequestBody CreateOrderRequest request, Locale locale, HttpSession session) {
        try {
            String boundTable = (String) session.getAttribute("tableNumber");
            boolean atTable = boundTable != null && settingsService.get().dineIn();
            Order order = atTable ? tableOrder(request, boundTable, locale) : takeawayOrder(request, locale);

            session.setAttribute("cart", order.items());
            session.setAttribute("orderNumber", order.orderNumber());
            session.setAttribute(SESSION_ORDER_ID, order.id());

            return Map.of("status", "success", "payFirst", order.heldUntilPaid());
        } catch (IllegalArgumentException e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    private Order tableOrder(CreateOrderRequest request, String boundTable, Locale locale) {
        List<CartItem> items = pricedItems(request, OrderChannel.TABLE);
        String tableId = tableService.findTableByNameOrId(boundTable).map(Table::id)
                .orElseThrow(() -> new IllegalArgumentException("Table does not exist"));
        return orderService.createOrderForTable(items, orderNumberService.next(), tableId, locale.getLanguage(),
                request.customerName());
    }

    /** Saved now, but held back from the kitchen until the guest has paid online. */
    private Order takeawayOrder(CreateOrderRequest request, Locale locale) {
        var settings = settingsService.get();
        if (!settings.takeaway() || !orderCheckoutService.isAvailable()) {
            throw new IllegalArgumentException(settings.dineIn()
                    ? "Scan the QR code on your table to order"
                    : "This restaurant is not taking orders online right now");
        }
        if (request.customerName() == null || request.customerName().isBlank()) {
            throw new IllegalArgumentException("Tell us your name so we can call you when the order is ready");
        }
        List<CartItem> items = pricedItems(request, OrderChannel.ONLINE);
        return orderService.createOrder(items, orderNumberService.next(), OrderPaymentStatus.UNPAID, OrderChannel.ONLINE,
                locale.getLanguage(), null, request.customerName().trim(), ServiceType.TAKEAWAY);
    }

    private List<CartItem> pricedItems(CreateOrderRequest request, OrderChannel channel) {
        List<CartItem> items = orderPricingService.price(request.items(), channel);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Your cart is empty");
        }
        return items;
    }

    /** Status of the order this guest placed; guests can only ever see their own order. */
    @ResponseBody
    @GetMapping({"/{tenantId}/api/order-status", "/api/order-status"})
    public ResponseEntity<Map<String, String>> orderStatus(HttpSession session) {
        return Optional.ofNullable((String) session.getAttribute(SESSION_ORDER_ID))
                .flatMap(orderService::findById)
                .map(order -> ResponseEntity.ok(Map.of("status", order.status().name())))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Real-time SSE stream for the guest's active order on confirmation page. */
    @GetMapping(value = {"/{tenantId}/api/order-status/live", "/api/order-status/live"}, produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter liveOrderStatus(HttpSession session) {
        String orderId = (String) session.getAttribute(SESSION_ORDER_ID);
        if (orderId == null) {
            org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(1000L);
            emitter.complete();
            return emitter;
        }
        return orderLiveEventService.subscribeOrder(orderId);
    }

    /** Real-time SSE stream for staff kitchen & counter dashboards. */
    @GetMapping(value = {"/{tenantId}/api/orders/live", "/api/orders/live"}, produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter liveOrdersStream(@PathVariable(required = false) String tenantId) {
        return orderLiveEventService.subscribeTenant(tenantId);
    }

    @ResponseBody
    @PostMapping({"/{tenantId}/api/order", "/api/order"})
    public List<Order> postOrder() {
        return orderService.getAllOrder();
    }

    @ResponseBody
    @PostMapping({"/{tenantId}/api/order/{id}/next", "/api/order/{id}/next"})
    public void nextOrderStatus(@PathVariable String id) {
        orderService.moveToNextStatus(id);
    }

    @ResponseBody
    @PostMapping({"/{tenantId}/api/order/{id}/previous", "/api/order/{id}/previous"})
    public void previousOrderStatus(@PathVariable String id) {
        orderService.moveToPreviousStatus(id);
    }

    @ResponseBody
    @PostMapping({"/{tenantId}/api/order/batch/next", "/api/order/batch/next"})
    public void batchNextStatus(@RequestBody List<String> ids) {
        orderService.batchMoveStatus(ids, true);
    }

    @ResponseBody
    @PostMapping({"/{tenantId}/api/order/batch/previous", "/api/order/batch/previous"})
    public void batchPreviousStatus(@RequestBody List<String> ids) {
        orderService.batchMoveStatus(ids, false);
    }

    @ResponseBody
    @PostMapping({"/{tenantId}/api/order/{id}/cancel", "/api/order/{id}/cancel"})
    public void cancelOrder(@PathVariable String id, @RequestBody OrderCancelReason orderCancelReason) {
        orderService.cancelOrder(id, orderCancelReason.value());
    }

    @ResponseBody
    @PostMapping({"/{tenantId}/api/order/{id}/status", "/api/order/{id}/status"})
    public void changeStatus(@PathVariable String id, @RequestBody OrderStatusChange orderStatusChange) {
        orderService.setOrderStatus(id, orderStatusChange.value());
    }

    @ResponseBody
    @PostMapping("/api/backoffice/orders/reassign-table")
    public Map<String, Object> reassignTable(@RequestParam String orderId, @RequestParam String tableId) {
        var table = tableService.findTableByOrderId(orderId);
        table.ifPresent(value -> tableService.unassignOrder(value.id(), orderId));
        tableService.assignOrder(tableId, orderId);
        return Map.of("status", "success");
    }

    @GetMapping({"/{tenantId}/order-confirmation", "/order-confirmation"})
    public String confirmation(@RequestParam(name = "session_id", required = false) String checkoutSessionId,
            HttpSession session, HttpServletRequest request, Model model) {
        var orderNumber = session.getAttribute("orderNumber");
        model.addAttribute("orderNumber", orderNumber);
        if (checkoutSessionId != null) {
            // Back from Stripe: ask Stripe whether the payment went through
            model.addAttribute("paidOnline", orderCheckoutService.confirmReturn(checkoutSessionId));
        }
        boolean stillToPay = Optional.ofNullable((String) session.getAttribute(SESSION_ORDER_ID))
                .flatMap(orderService::findById)
                .filter(Order::heldUntilPaid)
                .isPresent();
        if (stillToPay) {
            // Nothing is being prepared yet, so there is nothing to confirm: back to the payment page
            Object prefix = request.getAttribute(TenantRoutingResolver.TENANT_URL_PREFIX);
            return "redirect:" + (prefix != null ? prefix : "") + "/select-payment";
        }
        return "fastfood/confirmation";
    }

    @GetMapping({"/{tenantId}/dashboard", "/dashboard"})
    public String dashboard(Model model) {
        model.addAttribute("settings", settingsService.get());
        return "fastfood/dashboard";
    }
}
