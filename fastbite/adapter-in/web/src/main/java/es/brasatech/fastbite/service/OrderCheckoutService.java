package es.brasatech.fastbite.service;

import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.OrderStatus;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Online payment of a restaurant order: starts a Stripe checkout for what the order costs, and
 * marks the order paid once Stripe confirms the money arrived.
 */
@Service
@Slf4j
public class OrderCheckoutService {

    /** The tips a guest can add, as a percentage of the order total. */
    public static final List<Integer> TIP_PERCENTS = List.of(0, 10, 15, 20);

    private final StripeService stripeService;
    private final OrderService orderService;
    private final TenantLocationService tenantLocationService;
    private final String defaultConnectedAccount;

    public OrderCheckoutService(StripeService stripeService, OrderService orderService,
            TenantLocationService tenantLocationService,
            @Value("${stripe.connected-account:}") String defaultConnectedAccount) {
        this.stripeService = stripeService;
        this.orderService = orderService;
        this.tenantLocationService = tenantLocationService;
        this.defaultConnectedAccount = defaultConnectedAccount;
    }

    /** Whether guests of this installation can pay online. */
    public boolean isAvailable() {
        return stripeService.isConfigured();
    }

    /** Whether guests of a specific tenant location can pay online. */
    public boolean isAvailableForTenant(String tenantId) {
        if (!isAvailable()) {
            return false;
        }
        if (tenantId == null || tenantId.isBlank()) {
            return defaultConnectedAccount != null && !defaultConnectedAccount.isBlank();
        }
        return tenantLocationService.getLocation(tenantId)
                .map(TenantLocation::stripeAccountId)
                .filter(account -> !account.isBlank())
                .isPresent() || (defaultConnectedAccount != null && !defaultConnectedAccount.isBlank());
    }

    /**
     * Starts a checkout for the order's saved total plus the chosen tip.
     *
     * @throws IllegalArgumentException if the order cannot be paid (any more) or the tip is not one on offer
     */
    public Session start(String tenantId, Order order, int tipPercent, String successUrl, String cancelUrl)
            throws StripeException {
        if (order.paymentStatus() == OrderPaymentStatus.PAID) {
            throw new IllegalArgumentException("This order is already paid");
        }
        if (order.status() == OrderStatus.CANCELLED) {
            throw new IllegalArgumentException("This order was cancelled");
        }
        if (order.paymentExpired(java.time.LocalDateTime.now())) {
            throw new IllegalArgumentException("This order expired. Please place a new order.");
        }
        if (!TIP_PERCENTS.contains(tipPercent)) {
            throw new IllegalArgumentException("Choose one of the tips on offer");
        }
        BigDecimal tip = order.total().multiply(BigDecimal.valueOf(tipPercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        var locationOpt = tenantLocationService.getLocation(tenantId);
        String connectedAccount = locationOpt
                .map(TenantLocation::stripeAccountId)
                .filter(account -> !account.isBlank())
                .orElse(defaultConnectedAccount);

        long applicationFeeCents = 0L;
        boolean isFreePlan = locationOpt.map(loc -> "FREE".equalsIgnoreCase(loc.plan())).orElse(false);
        if (isFreePlan && connectedAccount != null && !connectedAccount.isBlank()) {
            BigDecimal orderTotalPlusTip = order.total().add(tip);
            long totalCents = StripeService.toCents(orderTotalPlusTip);
            applicationFeeCents = (totalCents * es.brasatech.fastbite.domain.tenant.SubscriptionPlan.FREE_PLAN_APPLICATION_FEE_BPS) / 10000;
        }

        return stripeService.createOrderCheckoutSession(tenantId, order, tip, connectedAccount, applicationFeeCents, successUrl, cancelUrl);
    }

    /**
     * For the page a guest lands on after paying: confirms the checkout with Stripe, as long as it
     * belongs to the restaurant this page is for.
     */
    public boolean confirmReturn(String checkoutSessionId) {
        try {
            Session session = stripeService.retrieveSession(checkoutSessionId);
            String tenantId = session.getMetadata() != null ? session.getMetadata().get("tenantId") : null;
            if (tenantId == null || !tenantId.equalsIgnoreCase(TenantContext.getCurrentTenant())) {
                log.warn("Checkout {} does not belong to restaurant {}", checkoutSessionId, TenantContext.getCurrentTenant());
                return false;
            }
            return confirm(session);
        } catch (StripeException | IllegalStateException e) {
            log.warn("Could not confirm checkout {}: {}", checkoutSessionId, e.getMessage());
            return false;
        }
    }

    /**
     * Marks the order of a checkout as paid if Stripe says it was paid in full. The session must come
     * from Stripe itself (retrieved with our key), never from a request. Safe to call more than once.
     *
     * @return whether the order is paid now
     */
    public boolean confirm(Session session) {
        Map<String, String> metadata = session.getMetadata();
        if (metadata == null || !StripeService.RESTAURANT_ORDER.equals(metadata.get("type"))
                || !"paid".equals(session.getPaymentStatus())) {
            return false;
        }
        String tenantId = metadata.get("tenantId");
        String orderId = metadata.get("orderId");
        if (tenantId == null || tenantId.isBlank() || orderId == null || orderId.isBlank()) {
            log.warn("Paid checkout {} names no restaurant order", session.getId());
            return false;
        }
        String requestTenant = TenantContext.getCurrentTenant();
        try {
            TenantContext.setCurrentTenant(tenantId);
            return markPaid(orderId, session);
        } finally {
            if (requestTenant != null) {
                TenantContext.setCurrentTenant(requestTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private boolean markPaid(String orderId, Session session) {
        Optional<Order> found = orderService.findById(orderId);
        if (found.isEmpty()) {
            log.error("Paid checkout {} is for order {}, which does not exist", session.getId(), orderId);
            return false;
        }
        Order order = found.get();
        if (order.paymentStatus() == OrderPaymentStatus.PAID) {
            return true;
        }
        long paid = session.getAmountTotal() != null ? session.getAmountTotal() : 0;
        long due = StripeService.toCents(order.total());
        if (paid < due) {
            // The order grew after the checkout started; staff have to collect the rest
            log.error("Checkout {} paid {} cents of the {} due for order {}", session.getId(), paid, due, orderId);
            return false;
        }
        orderService.markOrderPaid(orderId);
        log.info("Order {} paid online through checkout {}", orderId, session.getId());
        return true;
    }
}
