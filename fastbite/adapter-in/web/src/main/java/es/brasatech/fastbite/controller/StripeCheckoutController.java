package es.brasatech.fastbite.controller;

import com.stripe.model.checkout.Session;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.service.OrderCheckoutService;
import es.brasatech.fastbite.service.StripeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
@Slf4j
public class StripeCheckoutController {

    private final StripeService stripeService;
    private final OrderCheckoutService orderCheckoutService;
    private final OrderService orderService;

    /**
     * Level 2: a guest pays the order they placed in this browser session. The order and its total
     * come from the server; the page only chooses the tip.
     */
    @PostMapping({"/{tenantId}/api/stripe/create-checkout-session", "/api/stripe/create-checkout-session"})
    @ResponseBody
    public ResponseEntity<?> createRestaurantCheckoutSession(
            @RequestBody(required = false) Map<String, Object> payload,
            HttpSession httpSession,
            HttpServletRequest httpRequest) {

        String tenantId = TenantContext.getCurrentTenant();
        Optional<Order> order = Optional.ofNullable((String) httpSession.getAttribute("orderId"))
                .flatMap(orderService::findById);
        if (tenantId == null || order.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "There is no order to pay"));
        }

        try {
            String origin = ServletUriComponentsBuilder.fromContextPath(httpRequest).build().toUriString();
            Object prefix = httpRequest.getAttribute(TenantRoutingResolver.TENANT_URL_PREFIX);
            String baseUrl = origin + (prefix != null ? prefix : "");

            Session session = orderCheckoutService.start(
                    tenantId,
                    order.get(),
                    tipPercent(payload),
                    baseUrl + "/order-confirmation?session_id={CHECKOUT_SESSION_ID}",
                    baseUrl + "/select-payment");

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "checkoutUrl", session.getUrl(),
                    "sessionId", session.getId()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to create restaurant Stripe checkout session", e);
            return ResponseEntity.status(500).body(Map.of("error", "Online payment is not available right now"));
        }
    }

    private static int tipPercent(Map<String, Object> payload) {
        Object tip = payload != null ? payload.get("tipPercent") : null;
        try {
            return tip != null ? Integer.parseInt(tip.toString()) : 0;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Choose one of the tips on offer");
        }
    }

}
