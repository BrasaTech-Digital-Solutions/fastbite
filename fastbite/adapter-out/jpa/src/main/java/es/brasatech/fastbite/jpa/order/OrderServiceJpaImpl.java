package es.brasatech.fastbite.jpa.order;

import es.brasatech.fastbite.application.office.I18nConfig;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.domain.order.CartItem;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.OrderStatus;
import es.brasatech.fastbite.domain.order.ServiceType;
import es.brasatech.fastbite.domain.product.ProductCustomizer;
import es.brasatech.fastbite.domain.table.Table;
import es.brasatech.fastbite.domain.table.TableStatus;
import es.brasatech.fastbite.jpa.customization.CustomizationOptionEntity;
import es.brasatech.fastbite.jpa.customization.CustomizationOptionJpaRepository;
import es.brasatech.fastbite.jpa.i18n.Translations;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JPA implementation of OrderService for relational databases.
 * Handles i18n by storing all product customizer names in default language
 * and translating on retrieval based on orderLanguage field.
 */
@Service
@Profile("jpa")
@Transactional
public class OrderServiceJpaImpl implements OrderService {

    private final OrderJpaRepository repository;
    private final CustomizationOptionJpaRepository optionRepository;
    private final I18nConfig i18nConfig;
    private final TableService tableService;
    private final ApplicationEventPublisher eventPublisher;
    private final es.brasatech.fastbite.application.discount.DiscountService discountService;

    public OrderServiceJpaImpl(
            OrderJpaRepository repository,
            CustomizationOptionJpaRepository optionRepository,
            I18nConfig i18nConfig,
            TableService tableService,
            ApplicationEventPublisher eventPublisher,
            @org.springframework.context.annotation.Lazy es.brasatech.fastbite.application.discount.DiscountService discountService) {
        this.repository = repository;
        this.optionRepository = optionRepository;
        this.i18nConfig = i18nConfig;
        this.tableService = tableService;
        this.eventPublisher = eventPublisher;
        this.discountService = discountService;
    }

    @Override
    public List<Order> findAll() {
        expireAbandonedPayments();
        return repository.findAll().stream()
                .map(this::toOrderWithTranslation)
                .toList();
    }

    @Override
    public Optional<Order> findById(String id) {
        return repository.findForUpdate(id)
                .map(this::expirePayment)
                .map(this::toOrderWithTranslation);
    }

    private void expireAbandonedPayments() {
        repository.findExpiredPaymentIds(java.time.LocalDateTime.now().minusHours(1))
                .forEach(id -> repository.findForUpdate(id).ifPresent(this::expirePayment));
    }

    private OrderEntity expirePayment(OrderEntity entity) {
        var now = java.time.LocalDateTime.now();
        if (entity.getServiceType() == ServiceType.TAKEAWAY
                && entity.getOrderChannel() == es.brasatech.fastbite.domain.order.OrderChannel.ONLINE
                && entity.getPaymentStatus() == OrderPaymentStatus.UNPAID
                && entity.getStatus() == OrderStatus.CREATED && entity.getCreatedAt() != null
                && !entity.getCreatedAt().plusHours(1).isAfter(now)) {
            entity.setStatus(OrderStatus.CANCELLED);
            entity.setCancelReason(Order.PAYMENT_EXPIRED_REASON);
            entity.setUpdatedAt(now);
        }
        return entity;
    }

    /** Serialize payment with expiry, preserving saved prices and item identities. */
    @Override
    public void markOrderPaid(String id) {
        var entity = repository.findForUpdate(id).orElseThrow(() -> new IllegalArgumentException("Order not found"));
        if (entity.getPaymentStatus() == OrderPaymentStatus.PAID) {
            return;
        }
        // Stripe may deliver a successful payment after on-access cleanup has run.
        if (entity.getStatus() == OrderStatus.CANCELLED
                && Order.PAYMENT_EXPIRED_REASON.equals(entity.getCancelReason())) {
            entity.setStatus(OrderStatus.ACCEPTED);
            entity.setCancelReason(null);
        } else if (entity.getStatus() == OrderStatus.CREATED) {
            entity.setStatus(OrderStatus.ACCEPTED);
        }
        entity.setPaymentStatus(OrderPaymentStatus.PAID);
        entity.setUpdatedAt(java.time.LocalDateTime.now());
        tableService.findTableByOrderId(id).ifPresent(table -> tableService.resetTableSessionIfAllPaid(table.id()));
        publishEvent(new es.brasatech.fastbite.domain.event.OrderPaymentStatusChangedEvent(toOrderWithTranslation(entity)));
        publishEvent(new es.brasatech.fastbite.domain.event.OrderStatusChangedEvent(toOrderWithTranslation(entity)));
    }

    @Override
    public void markOrderRefunded(String id) {
        var entity = repository.findForUpdate(id).orElseThrow(() -> new IllegalArgumentException("Order not found"));
        entity.setPaymentStatus(OrderPaymentStatus.REFUNDED);
        entity.setUpdatedAt(java.time.LocalDateTime.now());
        publishEvent(new es.brasatech.fastbite.domain.event.OrderPaymentStatusChangedEvent(toOrderWithTranslation(entity)));
    }

    @Override
    public Order create(Order order) {
        OrderEntity entity = toEntityWithDefaultLanguage(order, false);
        OrderEntity saved = repository.save(entity);
        return toOrderWithTranslation(saved);
    }

    @Override
    public Optional<Order> update(String id, Order order) {
        if (!repository.existsById(id)) {
            return Optional.empty();
        }
        OrderEntity entity = toEntityWithDefaultLanguage(order, true);
        entity.setId(id);
        OrderEntity savedEntity = repository.save(entity);
        var savedOrder = toOrderWithTranslation(savedEntity);

        // Trigger table session reset check
        tableService.findTableByOrderId(id).ifPresent(table -> {
            tableService.resetTableSessionIfAllPaid(table.id());
        });

        return Optional.of(savedOrder);
    }

    @Override
    public boolean delete(String id) {
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        return true;
    }

    @Override
    public void clear() {
        repository.deleteAll();
    }

    @Override
    public List<Order> findActiveByTableId(String tableId) {
        return tableService.findById(tableId)
                .map(table -> repository.findAllById(table.orderIds())
                        .stream()
                        .filter(order -> order.getStatus() != OrderStatus.COMPLETE
                                && order.getStatus() != OrderStatus.CANCELLED)
                        .map(this::toOrderWithTranslation)
                        .toList())
                .orElse(List.of());
    }

    @Override
    public List<Order> findTakeawayAwaitingPayment() {
        return repository.findByServiceTypeAndPaymentStatusAndStatusNotInOrderByCreatedAt(ServiceType.TAKEAWAY,
                        OrderPaymentStatus.UNPAID, List.of(OrderStatus.COMPLETE, OrderStatus.CANCELLED))
                .stream()
                .map(this::toOrderWithTranslation)
                // Online orders are paid online, not at pickup; until then they are nobody's to collect
                .filter(order -> !order.heldUntilPaid())
                .toList();
    }

    @Override
    public void setTableStatus(String tableId, TableStatus status) {
        tableService.findById(tableId).ifPresent(table -> {
            List<String> orderIds = table.orderIds();
            if (status == TableStatus.AVAILABLE) {
                if (orderIds != null) {
                    for (String orderId : orderIds) {
                        repository.findById(orderId).ifPresent(order -> {
                            if (order.getStatus() != OrderStatus.CANCELLED) {
                                order.setStatus(OrderStatus.COMPLETE);
                                repository.save(order);
                            }
                        });
                    }
                }
                orderIds = List.of();
            }
            Table updatedTable = new Table(table.id(), table.name(), table.seats(), status, table.active(),
                    orderIds);
            tableService.update(tableId, updatedTable);
        });
    }

    @Override
    public void publishEvent(Object event) {
        eventPublisher.publishEvent(event);
    }

    @Override
    public Order createOrderForTable(List<CartItem> cartItems, int orderNumber, String tableNumber, String orderLanguage, String customerName) {
        String tableId = null;
        var opt = tableService.findById(tableNumber);
        if (opt.isPresent()) {
            tableId = opt.get().id();
        } else {
            var tables = tableService.findAll();
            for (var t : tables) {
                if (t.name().equalsIgnoreCase(tableNumber) || 
                    t.name().equalsIgnoreCase("Table " + tableNumber) || 
                    t.id().equals(tableNumber)) {
                    tableId = t.id();
                    break;
                }
            }
        }
        
        if (tableId == null) {
            throw new IllegalArgumentException("Table does not exist");
        }
        
        var savedOrder = createOrder(cartItems, orderNumber, es.brasatech.fastbite.domain.order.OrderPaymentStatus.UNPAID, es.brasatech.fastbite.domain.order.OrderChannel.TABLE, orderLanguage, null, customerName, ServiceType.DINE_IN);
        tableService.assignOrder(tableId, savedOrder.id());
        return savedOrder;
    }

    /**
     * Convert Order to OrderEntity, ensuring all ProductCustomizer names are in
     * default language.
     * If order language is not default, translate names to default language before
     * saving.
     *
     * @param order  The order to convert
     * @param setIds If true, sets IDs from order (for updates). If false, lets JPA
     *               generate IDs (for creates).
     */
    private OrderEntity toEntityWithDefaultLanguage(Order order, boolean setIds) {
        String defaultLang = i18nConfig.getDefaultLanguage();
        String orderLang = order.orderLanguage();

        OrderEntity entity = new OrderEntity();
        // Only set ID for updates, not for creates (JPA will generate it)
        if (setIds && order.id() != null) {
            entity.setId(order.id());
        }
        entity.setOrderNumber(order.orderNumber());
        entity.setCreatedAt(order.createdAt());
        entity.setUpdatedAt(order.updatedAt());
        entity.setStatus(order.status());

        // Calculate total considering discount deductions
        java.math.BigDecimal subtotal = order.items().stream()
                .map(CartItem::totalPrice)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        String calculatedTableId = null;
        if (order.id() != null) {
            calculatedTableId = tableService.findTableByOrderId(order.id()).map(Table::id).orElse(null);
        }
        java.math.BigDecimal discount = discountService.calculateDiscount(order.items(), null, calculatedTableId, order.orderChannel());
        java.math.BigDecimal orderTotal = subtotal.subtract(discount);
        if (orderTotal.compareTo(java.math.BigDecimal.ZERO) < 0) {
            orderTotal = java.math.BigDecimal.ZERO;
        }
        entity.setTotal(orderTotal);

        entity.setCancelReason(order.cancelReason());
        entity.setPaymentStatus(order.paymentStatus());
        entity.setOrderChannel(order.orderChannel());
        entity.setOrderLanguage(orderLang);
        entity.setUserId(order.userId());
        entity.setCustomerName(order.customerName());
        entity.setServiceType(order.serviceType());

        // Convert items
        if (order.items() != null) {
            List<CartItemEntity> itemEntities = new ArrayList<>();
            for (CartItem item : order.items()) {
                CartItemEntity itemEntity = new CartItemEntity();
                // Don't set CartItemEntity ID - let JPA generate it
                itemEntity.setItemId(item.itemId());
                itemEntity.setName(item.name());
                itemEntity.setDescription(item.description());
                itemEntity.setImage(item.image());
                itemEntity.setQuantity(item.quantity());
                itemEntity.setPrice(item.price());
                itemEntity.setVatRate(item.vatRate());

                // Convert customizations
                if (item.customizations() != null) {
                    List<ProductCustomizerEntity> customizerEntities = new ArrayList<>();
                    for (ProductCustomizer customizer : item.customizations()) {
                        ProductCustomizerEntity customizerEntity = new ProductCustomizerEntity();
                        // Always set ProductCustomizerEntity ID (it's the option ID, not
                        // auto-generated)
                        customizerEntity.setId(customizer.id());
                        customizerEntity.setPrice(customizer.price());
                        customizerEntity.setQuantity(customizer.quantity());

                        // If order language is not default, get the default language name
                        if (!orderLang.equals(defaultLang)) {
                            String defaultName = getDefaultLanguageName(customizer.id());
                            customizerEntity.setName(defaultName);
                        } else {
                            customizerEntity.setName(customizer.name());
                        }

                        customizerEntities.add(customizerEntity);
                    }
                    itemEntity.setCustomizations(customizerEntities);
                }

                itemEntities.add(itemEntity);
            }
            entity.setItems(itemEntities);
        }

        return entity;
    }

    /**
     * Get the default language name for a customization option.
     */
    private String getDefaultLanguageName(String optionId) {
        return optionRepository.findById(optionId)
                .map(CustomizationOptionEntity::getName)
                .orElse("");
    }

    /**
     * Convert OrderEntity to Order, translating ProductCustomizer names if needed.
     */
    private Order toOrderWithTranslation(OrderEntity entity) {
        String defaultLang = i18nConfig.getDefaultLanguage();
        String orderLang = entity.getOrderLanguage();

        List<CartItem> items = null;
        if (entity.getItems() != null) {
            items = new ArrayList<>();
            for (CartItemEntity itemEntity : entity.getItems()) {
                List<ProductCustomizer> customizers = null;
                if (itemEntity.getCustomizations() != null) {
                    customizers = new ArrayList<>();
                    for (ProductCustomizerEntity customizerEntity : itemEntity.getCustomizations()) {
                        String name;

                        // If order language is not default, translate the name
                        if (!orderLang.equals(defaultLang)) {
                            name = getTranslatedName(customizerEntity.getId(), orderLang, customizerEntity.getName());
                        } else {
                            name = customizerEntity.getName();
                        }

                        ProductCustomizer customizer = new ProductCustomizer(
                                customizerEntity.getId(),
                                name,
                                customizerEntity.getPrice(),
                                customizerEntity.getQuantity());
                        customizers.add(customizer);
                    }
                }

                CartItem item = new CartItem(
                        itemEntity.getId(),
                        itemEntity.getItemId(),
                        itemEntity.getName(),
                        itemEntity.getDescription(),
                        itemEntity.getImage(),
                        itemEntity.getQuantity(),
                        customizers,
                        itemEntity.getPrice(),
                        itemEntity.getVatRate());
                items.add(item);
            }
        }

        return new Order(
                items,
                entity.getOrderNumber(),
                entity.getId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getStatus(),
                entity.getTotal(),
                entity.getCancelReason(),
                entity.getPaymentStatus(),
                entity.getOrderChannel(),
                entity.getOrderLanguage(),
                entity.getUserId(),
                entity.getCustomerName(),
                entity.getServiceType() != null ? entity.getServiceType() : ServiceType.DINE_IN);
    }

    /**
     * Get translated name for a customization option, with fallback to default
     * language.
     */
    private String getTranslatedName(String optionId, String language, String defaultName) {
        return optionRepository.findById(optionId)
                .map(option -> Translations.get(option.getTranslations(), language, "name", defaultName))
                .orElse(defaultName);
    }
}
