package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.discount.DiscountService;
import es.brasatech.fastbite.application.order.OrderPricingService;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.settings.RestaurantSettingsService;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.domain.order.CartItem;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.dto.office.MenuDataService;
import es.brasatech.fastbite.service.OrderCheckoutService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
public class MenuController {

    private final MenuDataService menuDataService;
    private final TableService tableService;
    private final DiscountService discountService;
    private final OrderPricingService orderPricingService;
    private final OrderService orderService;
    private final RestaurantSettingsService settingsService;
    private final OrderCheckoutService orderCheckoutService;




    @GetMapping({"/{tenantId}/menu", "/menu"})
    @SuppressWarnings("unchecked")
    public String menu(
            @RequestParam(value = "table", required = false) String tableParam,
            @RequestParam(value = "token", required = false) String tokenParam,
            HttpSession session,
            HttpServletRequest request,
            Locale locale,
            Model model) {
        model.addAttribute("menuData", menuDataService.buildMenuData(locale));
        Object prefix = request.getAttribute(TenantRoutingResolver.TENANT_URL_PREFIX);
        model.addAttribute("allergenChartUrl", (prefix != null ? prefix : "") + "/allergens");
        model.addAttribute("allergenList", java.util.List.of(es.brasatech.fastbite.domain.allergen.Allergen.values()));
        try {
            String existingTableNumber = (String) session.getAttribute("tableNumber");
            List<CartItem> cartItems = (List<CartItem>) session.getAttribute("cart");
            String boundTableId = tableService.validateAndBindTableSession(tableParam, tokenParam, existingTableNumber, cartItems);
            
            if (boundTableId != null) {
                var tableOpt = tableService.findById(boundTableId);
                tableOpt.ifPresent(table -> {
                    session.setAttribute("tableNumber", table.id());
                    session.setAttribute("tableName", table.name());
                });
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("errorMessage", e.getMessage());
        }
        return "fastfood/menu";
    }

    @PostMapping({"/{tenantId}/api/calculate-cart", "/api/calculate-cart"})
    public String calculateCart(
            @RequestBody List<CartItem> cartItems,
            @RequestParam(required = false) String couponCode,
            HttpSession session,
            Model model) {
        String tableId = (String) session.getAttribute("tableNumber");
        calculate(cartItems, couponCode, tableId, model);
        return "fastfood/fragments/menu :: #cart";
    }

    @PostMapping({"/{tenantId}/api/calculate-confirmation", "/api/calculate-confirmation"})
    public String calculateConfirmation(
            @PathVariable(value = "tenantId", required = false) String tenantId,
            @RequestBody List<CartItem> cartItems,
            @RequestParam(required = false) String couponCode,
            HttpSession session,
            Model model) {
        String effectiveTenant = tenantId != null ? tenantId : es.brasatech.fastbite.domain.tenant.TenantContext.getCurrentTenant();
        String tableId = (String) session.getAttribute("tableNumber");
        calculate(cartItems, couponCode, tableId, model);
        // How this guest is served: at the table their QR code named, otherwise takeaway (paid online)
        var settings = settingsService.get();
        boolean atTable = tableId != null && settings.dineIn();
        boolean paymentAvailable = orderCheckoutService.isAvailableForTenant(effectiveTenant);
        boolean takeawayOffered = settings.takeaway() && paymentAvailable;
        model.addAttribute("atTable", atTable);
        model.addAttribute("canOrder", atTable || takeawayOffered);
        model.addAttribute("takeawayPaymentUnconfigured", !atTable && settings.takeaway() && !paymentAvailable);
        // A guest without a QR code could order to a table by scanning one
        model.addAttribute("scanHint", tableId == null && settings.dineIn());
        return "fastfood/fragments/menu :: #confirmation";
    }

    @PostMapping({"/{tenantId}/api/toast", "/api/toast"})
    public String getToast(@RequestBody Map<String, String> payload, Model model) {
        model.addAttribute("message", payload.get("message"));
        return "fastfood/fragments/menu :: toast";
    }

    /** The payment page for the order this guest just placed, as it was saved. */
    @GetMapping({"/{tenantId}/select-payment", "/select-payment"})
    public String selectPayment(HttpSession session, HttpServletRequest request, Model model) {
        Optional<Order> order = Optional.ofNullable((String) session.getAttribute("orderId"))
                .flatMap(orderService::findById);
        if (order.isEmpty()) {
            Object prefix = request.getAttribute(TenantRoutingResolver.TENANT_URL_PREFIX);
            return "redirect:" + (prefix != null ? prefix : "") + "/menu";
        }
        model.addAttribute("order", order.get());
        if (order.get().status() == es.brasatech.fastbite.domain.order.OrderStatus.CANCELLED) {
            return "fastfood/orderCancelled";
        }
        model.addAttribute("subtotal", order.get().subtotal());
        model.addAttribute("discount", order.get().subtotal().subtract(order.get().total()).max(BigDecimal.ZERO));
        model.addAttribute("tipPercents", OrderCheckoutService.TIP_PERCENTS);
        model.addAttribute("payFirst", order.get().heldUntilPaid());
        return "fastfood/paymentSelection";
    }

    private void calculate(List<CartItem> requested, String couponCode, String tableId, Model model) {
        List<CartItem> cartItems = orderPricingService.price(requested, OrderChannel.TABLE);
        var breakdown = discountService.calculateCartBreakdown(cartItems, couponCode, tableId);

        model.addAttribute("cart", cartItems);
        model.addAttribute("subtotal", breakdown.subtotal());
        model.addAttribute("discount", breakdown.discount());
        model.addAttribute("tax", breakdown.tax());
        model.addAttribute("vat", breakdown.vat());
        model.addAttribute("total", breakdown.total());
    }
}
