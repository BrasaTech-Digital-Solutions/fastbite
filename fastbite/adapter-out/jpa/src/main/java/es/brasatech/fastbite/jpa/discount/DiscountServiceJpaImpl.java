package es.brasatech.fastbite.jpa.discount;

import es.brasatech.fastbite.application.discount.DiscountService;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.domain.discount.DiscountRule;
import es.brasatech.fastbite.domain.discount.DiscountScope;
import es.brasatech.fastbite.domain.discount.DiscountType;
import es.brasatech.fastbite.domain.order.CartItem;
import es.brasatech.fastbite.domain.order.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional
public class DiscountServiceJpaImpl implements DiscountService {

    private final DiscountRuleJpaRepository repository;
    private final OrderService orderService;

    @Override
    public List<DiscountRule> findAll() {
        return repository.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<DiscountRule> findById(String id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public DiscountRule create(DiscountRule rule) {
        DiscountRuleEntity entity = new DiscountRuleEntity();
        entity.setId(rule.id() != null && !rule.id().isEmpty() ? rule.id() : UUID.randomUUID().toString());
        entity.setName(rule.name());
        entity.setScope(rule.scope());
        entity.setType(rule.type());
        entity.setValue(rule.value());
        entity.setMinSubtotal(rule.minSubtotal());
        entity.setCouponCode(rule.couponCode());
        entity.setActive(rule.active());
        entity.setAccumulative(rule.accumulative());
        entity.setApplyOnCounter(rule.applyOnCounter());
        return toDomain(repository.save(entity));
    }

    @Override
    public Optional<DiscountRule> update(String id, DiscountRule rule) {
        return repository.findById(id).map(entity -> {
            entity.setName(rule.name());
            entity.setScope(rule.scope());
            entity.setType(rule.type());
            entity.setValue(rule.value());
            entity.setMinSubtotal(rule.minSubtotal());
            entity.setCouponCode(rule.couponCode());
            entity.setActive(rule.active());
            entity.setAccumulative(rule.accumulative());
            entity.setApplyOnCounter(rule.applyOnCounter());
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
    public BigDecimal calculateDiscount(List<CartItem> items, String couponCode, String tableId) {
        return calculateDiscount(items, couponCode, tableId, es.brasatech.fastbite.domain.order.OrderChannel.TABLE);
    }

    @Override
    public BigDecimal calculateDiscount(List<CartItem> items, String couponCode, String tableId, es.brasatech.fastbite.domain.order.OrderChannel channel) {
        BigDecimal orderSubtotal = items.stream()
                .map(CartItem::totalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Fetch active table orders to aggregate combined subtotals
        BigDecimal tableSubtotal = orderSubtotal;
        if (tableId != null && !tableId.isEmpty()) {
            List<Order> activeOrders = orderService.findActiveByTableId(tableId);
            BigDecimal collectiveTotal = activeOrders.stream()
                    .map(Order::total)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            tableSubtotal = tableSubtotal.add(collectiveTotal);
        }

        List<DiscountRuleEntity> rules = repository.findAll().stream().filter(DiscountRuleEntity::isActive).toList();
        
        // Filter rules that match the channel source:
        // By default, rules apply to self-order views (TABLE/ONLINE/WAITER).
        // If order channel is COUNTER, the rule MUST have applyOnCounter set to true.
        List<DiscountRuleEntity> activeRules = rules.stream().filter(rule -> {
            if (channel == es.brasatech.fastbite.domain.order.OrderChannel.COUNTER) {
                return rule.isApplyOnCounter();
            }
            return true;
        }).toList();

        // Find if there is a manual/coupon code that matches and evaluate it first
        Optional<DiscountRuleEntity> couponRuleOpt = activeRules.stream()
                .filter(r -> r.getCouponCode() != null && !r.getCouponCode().isEmpty() && couponCode != null && r.getCouponCode().equalsIgnoreCase(couponCode.trim()))
                .findFirst();

        BigDecimal totalDiscount = BigDecimal.ZERO;
        boolean hasCouponDiscountApplied = false;

        if (couponRuleOpt.isPresent()) {
            DiscountRuleEntity couponRule = couponRuleOpt.get();
            BigDecimal subtotalToEvaluate = couponRule.getScope() == DiscountScope.TABLE ? tableSubtotal : orderSubtotal;
            if (subtotalToEvaluate.compareTo(couponRule.getMinSubtotal()) >= 0) {
                BigDecimal ruleDiscount;
                if (couponRule.getType() == DiscountType.PERCENTAGE) {
                    ruleDiscount = orderSubtotal.multiply(couponRule.getValue().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
                } else {
                    ruleDiscount = couponRule.getValue();
                }

                if (couponRule.getScope() == DiscountScope.TABLE && tableId != null && !tableId.isEmpty() && tableSubtotal.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal splitFactor = orderSubtotal.divide(tableSubtotal, 4, RoundingMode.HALF_UP);
                    ruleDiscount = ruleDiscount.multiply(splitFactor);
                }

                totalDiscount = totalDiscount.add(ruleDiscount);
                hasCouponDiscountApplied = true;

                // If coupon is NOT accumulative, we stop and do not apply automatic discounts
                if (!couponRule.isAccumulative()) {
                    if (totalDiscount.compareTo(orderSubtotal) > 0) {
                        totalDiscount = orderSubtotal;
                    }
                    return totalDiscount.setScale(2, RoundingMode.HALF_UP);
                }
            }
        }

        // Apply matching automatic discounts (where coupon code is null or empty)
        for (DiscountRuleEntity rule : activeRules) {
            if (rule.getCouponCode() != null && !rule.getCouponCode().isEmpty()) {
                continue; // Skip coupon rules since they are handled explicitly
            }

            BigDecimal subtotalToEvaluate = rule.getScope() == DiscountScope.TABLE ? tableSubtotal : orderSubtotal;
            if (subtotalToEvaluate.compareTo(rule.getMinSubtotal()) >= 0) {
                BigDecimal ruleDiscount;
                if (rule.getType() == DiscountType.PERCENTAGE) {
                    ruleDiscount = orderSubtotal.multiply(rule.getValue().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
                } else {
                    ruleDiscount = rule.getValue();
                }

                if (rule.getScope() == DiscountScope.TABLE && tableId != null && !tableId.isEmpty() && tableSubtotal.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal splitFactor = orderSubtotal.divide(tableSubtotal, 4, RoundingMode.HALF_UP);
                    ruleDiscount = ruleDiscount.multiply(splitFactor);
                }

                totalDiscount = totalDiscount.add(ruleDiscount);

                // If this automatic discount is applied and is NOT accumulative, stop evaluating further rules
                if (!rule.isAccumulative()) {
                    break;
                }
            }
        }

        // Limit total discount to subtotal
        if (totalDiscount.compareTo(orderSubtotal) > 0) {
            totalDiscount = orderSubtotal;
        }

        return totalDiscount.setScale(2, RoundingMode.HALF_UP);
    }

    private DiscountRule toDomain(DiscountRuleEntity entity) {
        return new DiscountRule(
            entity.getId(),
            entity.getName(),
            entity.getScope(),
            entity.getType(),
            entity.getValue(),
            entity.getMinSubtotal(),
            entity.getCouponCode(),
            entity.isActive(),
            entity.isAccumulative(),
            entity.isApplyOnCounter()
        );
    }
}
