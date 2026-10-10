package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.office.CustomizationService;
import es.brasatech.fastbite.application.office.GroupService;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.application.order.OrderNumberService;
import es.brasatech.fastbite.application.order.OrderPricingService;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.payment.PaymentService;
import es.brasatech.fastbite.application.settings.RestaurantSettingsService;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.domain.order.CartItem;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.ServiceType;
import es.brasatech.fastbite.domain.table.TableStatus;
import es.brasatech.fastbite.dto.counter.CounterOrderRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class CounterController {

    private final TableService tableService;
    private final PaymentService paymentService;
    private final OrderService orderService;
    private final OrderNumberService orderNumberService;
    private final OrderPricingService orderPricingService;
    private final GroupService groupService;
    private final ProductService productService;
    private final CustomizationService customizationService;
    private final es.brasatech.fastbite.application.discount.DiscountService discountService;
    private final RestaurantSettingsService settingsService;
    private final es.brasatech.fastbite.application.fiscal.FiscalTicketService fiscalTicketService;
    private final es.brasatech.fastbite.application.fiscal.FiscalProfileService fiscalProfileService;

    @GetMapping({"/{tenantId}/counter", "/counter"})
    public String counter(Model model) {
        model.addAttribute("tables", tableService.findAll());
        model.addAttribute("settings", settingsService.get());
        model.addAttribute("ticketsReady", fiscalTicketService.ready());
        model.addAttribute("trainingTickets", fiscalTicketService.training());
        var config = paymentService.getActiveConfig();
        model.addAttribute("paymentConfig", Map.of(
            "activeModes", new java.util.ArrayList<>(config.activeModes()),
            "moneyDenominations", config.moneyDenominations()
        ));
        return "fastfood/counter";
    }

    @ResponseBody
    @es.brasatech.fastbite.config.RequiresOrderingSubscription
    @PostMapping("/counter/api/order")
    public Map<String, Object> createOrder(
            @RequestBody CounterOrderRequest request,
            @AuthenticationPrincipal UserDetails userDetails,
            Locale locale) {

        boolean forTable = request.tableId() != null && !request.tableId().isEmpty();
        ServiceType serviceType = forTable ? ServiceType.DINE_IN : ServiceType.TAKEAWAY;
        // A sale paid on the spot always works; tables and pay-at-pickup are the restaurant's choice
        if ((forTable || !request.paid()) && !settingsService.get().offers(serviceType)) {
            throw new IllegalArgumentException(forTable
                    ? "Table orders are switched off for this restaurant"
                    : "Takeaway orders are switched off for this restaurant");
        }
        List<CartItem> items = orderPricingService.price(request.items(), OrderChannel.COUNTER);
        es.brasatech.fastbite.domain.fiscal.PaymentMethod method = null;
        if (request.paid()) {
            // Refuse before an order number is taken: a sale paid on the spot needs its ticket
            method = es.brasatech.fastbite.domain.fiscal.PaymentMethod.of(request.paymentMethod());
            if (!fiscalTicketService.ready()) {
                throw new es.brasatech.fastbite.domain.exception.LocalizedRejection("ticket.error.notReady",
                        es.brasatech.fastbite.application.fiscal.FiscalTicketService.NOT_READY);
            }
            if (request.invoice() != null) {
                request.invoice().toRecipient().validated();
            }
        }
        var orderNumber = orderNumberService.next();

        // Created unpaid; taking the payment below marks it paid and issues its ticket together
        Order order = orderService.createOrder(
                items,
                orderNumber,
                OrderPaymentStatus.UNPAID,
                OrderChannel.COUNTER,
                locale.getLanguage(),
                userDetails.getUsername(),
                request.customerName(),
                serviceType);

        if (forTable) {
            tableService.assignOrder(request.tableId(), order.id());
            // Saving again applies the discounts that depend on the table
            order = orderService.update(order.id(), order).orElse(order);
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("status", "success");
        result.put("orderNumber", orderNumber);
        result.put("orderId", order.id());
        if (request.paid()) {
            try {
                var ticket = fiscalTicketService.settle(List.of(order.id()),
                        es.brasatech.fastbite.application.fiscal.FiscalTicketService.Payment.of(method, request.cashReceived()),
                        request.invoice() == null ? null : request.invoice().toRecipient(), userDetails.getUsername());
                result.putAll(TicketController.summary(ticket));
            } catch (RuntimeException e) {
                // Nothing was charged: the order must not reach the kitchen as if it were paid for
                orderService.cancelOrder(order.id(), "Payment not completed at the counter");
                throw e;
            }
        }
        return result;
    }

    @ResponseBody
    @GetMapping("/counter/api/tables/{tableId}/active-orders")
    public List<Order> getActiveOrders(@PathVariable String tableId) {
        return orderService.findActiveByTableId(tableId);
    }

    @ResponseBody
    @GetMapping("/counter/api/takeaway/orders")
    public List<Order> getTakeawayOrdersAwaitingPayment() {
        return orderService.findTakeawayAwaitingPayment();
    }

    @ResponseBody
    @PostMapping("/counter/api/tables/{tableId}/status")
    public Map<String, Object> updateTableStatus(@PathVariable String tableId, @RequestParam TableStatus status) {
        orderService.setTableStatus(tableId, status);
        return Map.of("status", "success");
    }

    @ResponseBody
    @PutMapping("/counter/api/orders/{orderId}")
    public Map<String, Object> updateOrder(
            @PathVariable String orderId,
            @RequestBody CounterOrderRequest request) {

        var existingOrder = orderService.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        requireUnpaid(existingOrder);

        List<CartItem> items = orderPricingService.price(request.items(), OrderChannel.COUNTER);
        orderService.update(orderId, existingOrder.withItems(items));
        return Map.of("status", "success", "orderId", orderId);
    }

    /**
     * Takes payment for an order as it was placed and issues its ticket; its lines and prices stay
     * untouched. The counter now pays through /counter/api/settle; this stays for older screens.
     */
    @ResponseBody
    @PostMapping("/counter/api/orders/{orderId}/pay")
    public Map<String, Object> payOrder(@PathVariable String orderId,
            @RequestParam(defaultValue = "CASH") String method,
            @AuthenticationPrincipal UserDetails userDetails) {
        var ticket = fiscalTicketService.settle(List.of(orderId),
                es.brasatech.fastbite.application.fiscal.FiscalTicketService.Payment.of(
                        es.brasatech.fastbite.domain.fiscal.PaymentMethod.of(method), null),
                null, userDetails.getUsername());
        Map<String, Object> result = new java.util.LinkedHashMap<>(TicketController.summary(ticket));
        result.put("status", "success");
        result.put("orderId", orderId);
        return result;
    }

    @ResponseBody
    @PostMapping("/counter/api/orders/{orderId}/cancel")
    public Map<String, Object> cancelOrder(@PathVariable String orderId,
            @RequestParam(required = false) String reason) {
        orderService.findById(orderId).ifPresent(this::requireUnpaid);
        orderService.cancelOrder(orderId, reason != null ? reason : "Canceled at Counter");
        return Map.of("status", "success");
    }

    /** A paid order has a ticket: changing it would make the ticket wrong, so it is refunded instead. */
    private void requireUnpaid(Order order) {
        if (order.paymentStatus() != OrderPaymentStatus.UNPAID) {
            throw new es.brasatech.fastbite.domain.exception.LocalizedRejection("ticket.error.paidOrderLocked",
                    "This order is paid. To change it, refund its ticket first.");
        }
    }

    @ResponseBody
    @PostMapping("/counter/api/orders/{orderId}/reassign")
    public Map<String, Object> reassignOrder(@PathVariable String orderId, @RequestParam String tableId) {
        tableService.findTableByOrderId(orderId).ifPresent(table -> {
            tableService.unassignOrder(table.id(), orderId);
        });

        tableService.assignOrder(tableId, orderId);
        return Map.of("status", "success");
    }

    // ===== Fragment Endpoints =====

    @GetMapping("/counter/fragments/categories")
    public String getCategoriesFragment(Model model,
            @RequestParam(required = false, defaultValue = "all") String selectedGroup) {
        model.addAttribute("categories", groupService.findAll());
        model.addAttribute("selectedGroup", selectedGroup);
        return "fastfood/fragments/counter :: categories";
    }

    @GetMapping("/counter/fragments/products")
    public String getProductsFragment(Model model,
            @RequestParam(required = false, defaultValue = "all") String groupId,
            @RequestParam(required = false, defaultValue = "") String filter) {

        var products = productService.findAll().stream()
                .filter(p -> {
                    boolean matchesFilter = p.name().toLowerCase().contains(filter.toLowerCase());
                    boolean matchesGroup = "all".equals(groupId);
                    if (!matchesGroup) {
                        matchesGroup = groupService.findById(groupId)
                                .map(g -> g.products() != null && g.products().contains(p.id()))
                                .orElse(false);
                    }
                    return matchesFilter && matchesGroup;
                })
                .toList();

        model.addAttribute("products", products);
        return "fastfood/fragments/counter :: products";
    }

    @GetMapping("/counter/fragments/tables")
    public String getTablesFragment(Model model, @RequestParam(required = false) String selectedTableId) {
        model.addAttribute("tables", tableService.findAll());
        model.addAttribute("selectedTableId", selectedTableId);
        return "fastfood/fragments/counter :: tables";
    }

    @GetMapping("/counter/fragments/reassign-tables")
    public String getReassignTablesFragment(Model model, @RequestParam(required = false) String currentTableId) {
        model.addAttribute("tables", tableService.findAll());
        model.addAttribute("currentTableId", currentTableId);
        return "fastfood/fragments/counter :: reassign-tables";
    }

    @GetMapping("/counter/fragments/active-orders/{tableId}")
    public String getActiveOrdersFragment(Model model, @PathVariable String tableId) {
        var orders = orderService.findActiveByTableId(tableId);
        var enriched = orders.stream().map(o -> Map.of(
                "id", o.id(),
                "orderNumber", o.orderNumber(),
                "total", o.total(),
                "items", o.items(),
                "createdAt", o.createdAt())).toList();
        model.addAttribute("orders", enriched);
        return "fastfood/fragments/counter :: active-orders";
    }

    @PostMapping("/counter/fragments/order-cart")
    public String getOrderCartFragment(Model model, @RequestBody List<CartItem> requested, jakarta.servlet.http.HttpServletResponse response) {
        List<CartItem> items = orderPricingService.price(requested, OrderChannel.COUNTER);
        // Enriched list for the fragment
        var enrichedItems = items.stream().map(item -> {
            boolean customizable = productService.findById(item.itemId())
                    .map(p -> p.customizations() != null && !p.customizations().isEmpty())
                    .orElse(false);

            return Map.of(
                    "name", item.name(),
                    "price", item.price(),
                    "quantity", item.quantity(),
                    "customizations", item.customizations(),
                    "customizable", customizable);
        }).toList();

        var subtotal = items.stream().map(CartItem::totalPrice).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        var discount = discountService.calculateDiscount(items, null, null, es.brasatech.fastbite.domain.order.OrderChannel.COUNTER);
        var total = subtotal.subtract(discount);
        if (total.compareTo(java.math.BigDecimal.ZERO) < 0) {
            total = java.math.BigDecimal.ZERO;
        }

        model.addAttribute("items", enrichedItems);
        model.addAttribute("subtotal", subtotal);
        model.addAttribute("discount", discount);
        model.addAttribute("total", total);

        response.setHeader("X-Cart-Subtotal", subtotal.toString());
        response.setHeader("X-Cart-Discount", discount.toString());
        response.setHeader("X-Cart-Total", total.toString());

        return "fastfood/fragments/counter :: order-cart";
    }

    /** The open orders of one table, or without a table the takeaway orders still to be paid. */
    @GetMapping({"/counter/fragments/table-session-cart/{tableId}", "/counter/fragments/takeaway-session-cart"})
    public String getTableSessionCartFragment(Model model, @PathVariable(required = false) String tableId,
            @RequestParam(required = false) List<Integer> expandedIndices,
            jakarta.servlet.http.HttpServletResponse response) {
        var orders = tableId != null ? orderService.findActiveByTableId(tableId) : orderService.findTakeawayAwaitingPayment();

        // Wrap orders to include expansion state and simplify data for fragment
        var wrappedOrders = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < orders.size(); i++) {
            var order = orders.get(i);

            var itemMaps = order.items().stream().map(item -> {
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("name", item.name());
                m.put("quantity", item.quantity());
                m.put("totalPrice", item.totalPrice());
                m.put("customizations", item.customizations());
                return m;
            }).toList();

            java.math.BigDecimal orderSubtotal = order.subtotal();
            java.math.BigDecimal orderDiscount = orderSubtotal.subtract(order.total());
            if (orderDiscount.compareTo(java.math.BigDecimal.ZERO) < 0) {
                orderDiscount = java.math.BigDecimal.ZERO;
            }

            Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", order.id());
            map.put("orderNumber", order.orderNumber());
            map.put("customerName", order.customerName());
            map.put("subtotal", orderSubtotal);
            map.put("discount", orderDiscount);
            map.put("total", order.total());
            map.put("status", order.status() != null ? order.status().name() : "CREATED");
            map.put("paymentStatus", order.paymentStatus() != null ? order.paymentStatus().name() : "UNPAID");
            map.put("items", itemMaps);
            map.put("expanded", expandedIndices != null && expandedIndices.contains(i));

            wrappedOrders.add(map);
        }

        // Calculate combined unpaid table totals
        java.math.BigDecimal subtotal = java.math.BigDecimal.ZERO;
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;

        for (var o : orders) {
            if (o.paymentStatus() != es.brasatech.fastbite.domain.order.OrderPaymentStatus.PAID && o.status() != es.brasatech.fastbite.domain.order.OrderStatus.CANCELLED) {
                // Table orders are already discounted/stored. Sum their items totalPrice as raw subtotal.
                subtotal = subtotal.add(o.subtotal());
                total = total.add(o.total());
            }
        }

        java.math.BigDecimal discount = subtotal.subtract(total);
        if (discount.compareTo(java.math.BigDecimal.ZERO) < 0) {
            discount = java.math.BigDecimal.ZERO;
        }

        response.setHeader("X-Cart-Subtotal", subtotal.toString());
        response.setHeader("X-Cart-Discount", discount.toString());
        response.setHeader("X-Cart-Total", total.toString());

        model.addAttribute("orders", wrappedOrders);
        model.addAttribute("takeaway", tableId == null);
        return "fastfood/fragments/counter :: table-session-cart";
    }

    @GetMapping("/counter/fragments/customization-options/{productId}")
    public String getCustomizationOptionsFragment(Model model,
            @PathVariable String productId,
            @RequestParam(required = false) List<String> selected) {

        var product = productService.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found"));

        var customizations = product.customizations().stream()
                .map(id -> customizationService.findById(id).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();

        model.addAttribute("customizations", customizations);
        model.addAttribute("selectedOptions", selected != null ? selected : List.of());
        return "fastfood/fragments/counter :: customization-options";
    }

    /**
     * The bill shown to guests before they pay (pro forma). It is not an invoice: the ticket is
     * issued when the payment is taken.
     */
    @GetMapping("/counter/receipt")
    public String getReceipt(
            @RequestParam("ids") List<String> orderIds,
            Model model) {

        var orders = orderIds.stream()
                .map(id -> orderService.findById(id).orElseThrow(() -> new RuntimeException("Order not found: " + id)))
                .toList();

        var rawSubtotal = orders.stream()
                .flatMap(o -> o.items().stream())
                .map(item -> item.totalPrice())
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        var grandTotal = orders.stream()
                .map(o -> o.total())
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        var discount = rawSubtotal.subtract(grandTotal);
        if (discount.compareTo(java.math.BigDecimal.ZERO) < 0) {
            discount = java.math.BigDecimal.ZERO;
        }

        var vat = es.brasatech.fastbite.domain.fiscal.VatBreakdown.of(orders.stream()
                .flatMap(o -> o.items().stream())
                .map(item -> new es.brasatech.fastbite.domain.fiscal.VatBreakdown.Amount(item.totalPrice(), item.vatRate()))
                .toList(), discount);

        model.addAttribute("orders", orders);
        model.addAttribute("rawSubtotal", rawSubtotal);
        model.addAttribute("discount", discount);
        model.addAttribute("grandTotal", grandTotal);
        model.addAttribute("vat", vat);
        model.addAttribute("profile", fiscalProfileService.get());

        return "fastfood/receipt";
    }
}
