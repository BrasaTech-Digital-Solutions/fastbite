package es.brasatech.fastbite.jpa.order;

import es.brasatech.fastbite.application.office.I18nConfig;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.domain.order.CartItem;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.OrderStatus;
import es.brasatech.fastbite.domain.table.Table;
import es.brasatech.fastbite.domain.product.ProductCustomizer;
import es.brasatech.fastbite.jpa.customization.CustomizationOptionEntity;
import es.brasatech.fastbite.jpa.customization.CustomizationOptionJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceJpaImplTest {

    @Mock
    private OrderJpaRepository repository;

    @Mock
    private CustomizationOptionJpaRepository optionRepository;

    @Mock
    private I18nConfig i18nConfig;

    @Mock
    private es.brasatech.fastbite.application.discount.DiscountService discountService;

    @Mock
    private es.brasatech.fastbite.application.settings.RestaurantSettingsService restaurantSettingsService;

    @Mock
    private TableService tableService;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private OrderServiceJpaImpl service;

    private static final String DEFAULT_LANG = "en";
    private static final String OPTION_ID = "cust-1-opt-0";

    @BeforeEach
    void setUp() {
        when(i18nConfig.getDefaultLanguage()).thenReturn(DEFAULT_LANG);
        when(discountService.calculateDiscount(any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void create_withDefaultLanguage_shouldSaveDirectly() {
        // Given
        ProductCustomizer customizer = new ProductCustomizer(OPTION_ID, "Cheese", BigDecimal.valueOf(1.5), 1);
        CartItem item = new CartItem("item1", "prod1", "Burger", "Delicious", "img.jpg",
                1, List.of(customizer), BigDecimal.TEN);
        Order order = new Order(List.of(item), 1, OrderPaymentStatus.PAID, OrderChannel.WAITER, DEFAULT_LANG);

        OrderEntity savedEntity = createOrderEntity(order);
        when(repository.save(any(OrderEntity.class))).thenReturn(savedEntity);

        // When
        Order result = service.create(order);

        // Then
        assertThat(result.orderLanguage()).isEqualTo(DEFAULT_LANG);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).customizations()).hasSize(1);
        assertThat(result.items().get(0).customizations().get(0).name()).isEqualTo("Cheese");

        verify(optionRepository, never()).findById(any());
    }

    @Test
    void create_withNonDefaultLanguage_shouldTranslateToDefaultLanguage() {
        // Given - order comes in Spanish
        ProductCustomizer customizer = new ProductCustomizer(OPTION_ID, "Queso", BigDecimal.valueOf(1.5), 1);
        CartItem item = new CartItem("item1", "prod1", "Hamburguesa", "Deliciosa", "img.jpg",
                1, List.of(customizer), BigDecimal.TEN);
        Order order = new Order(List.of(item), 1, OrderPaymentStatus.PAID, OrderChannel.WAITER, "es");

        // Mock: option repository returns default language name
        CustomizationOptionEntity optionEntity = new CustomizationOptionEntity();
        optionEntity.setId(OPTION_ID);
        optionEntity.setName("Cheese");
        when(optionRepository.findById(OPTION_ID)).thenReturn(Optional.of(optionEntity));

        OrderEntity savedEntity = createOrderEntity(order);
        savedEntity.getItems().get(0).getCustomizations().get(0).setName("Cheese");
        when(repository.save(any(OrderEntity.class))).thenReturn(savedEntity);

        optionEntity.setTranslations(Map.of("es", Map.of("name", "Queso")));

        // When
        Order result = service.create(order);

        // Then - the default language name was looked up for storage
        verify(optionRepository, atLeastOnce()).findById(OPTION_ID);

        // The result should have the Spanish translation
        assertThat(result.items().get(0).customizations().get(0).name()).isEqualTo("Queso");
    }

    @Test
    void createOrderForTable_whenSupervised_routesToCreated() {
        Table table = new Table("tbl-1", "Table 1", 4, es.brasatech.fastbite.domain.table.TableStatus.AVAILABLE, true, List.of(), null, null, null);
        when(tableService.findTableByNameOrId("tbl-1")).thenReturn(Optional.of(table));
        when(restaurantSettingsService.get()).thenReturn(new es.brasatech.fastbite.domain.settings.RestaurantSettings(true, true, 0, 0, false));
        when(repository.countRecentTableQrOrders(eq("tbl-1"), any())).thenReturn(0L);
        when(repository.save(any(OrderEntity.class))).thenAnswer(inv -> {
            OrderEntity e = inv.getArgument(0);
            e.setId("ord-1");
            return e;
        });

        Order created = service.createOrderForTable(List.of(), 101, "tbl-1", "en", "Guest");

        assertThat(created.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(created.cancelReason()).isNull();
        verify(tableService).assignOrder("tbl-1", "ord-1");
    }

    @Test
    void createOrderForTable_whenTrusted_routesToAccepted() {
        Table table = new Table("tbl-1", "Table 1", 4, es.brasatech.fastbite.domain.table.TableStatus.AVAILABLE, true, List.of(), true, "staff", java.time.LocalDateTime.now());
        when(tableService.findTableByNameOrId("tbl-1")).thenReturn(Optional.of(table));
        when(restaurantSettingsService.get()).thenReturn(new es.brasatech.fastbite.domain.settings.RestaurantSettings(true, true, 0, 0, false));
        when(repository.countRecentTableQrOrders(eq("tbl-1"), any())).thenReturn(1L);
        when(repository.save(any(OrderEntity.class))).thenAnswer(inv -> {
            OrderEntity e = inv.getArgument(0);
            e.setId("ord-2");
            return e;
        });

        Order created = service.createOrderForTable(List.of(), 102, "tbl-1", "en", "Guest");

        assertThat(created.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(created.cancelReason()).isNull();
        verify(tableService).assignOrder("tbl-1", "ord-2");
    }

    @Test
    void createOrderForTable_whenTrusted_andBrakeTripped_routesToCreatedWithApprovalNotice() {
        Table table = new Table("tbl-1", "Table 1", 4, es.brasatech.fastbite.domain.table.TableStatus.AVAILABLE, true, List.of(), true, "staff", java.time.LocalDateTime.now());
        when(tableService.findTableByNameOrId("tbl-1")).thenReturn(Optional.of(table));
        when(restaurantSettingsService.get()).thenReturn(new es.brasatech.fastbite.domain.settings.RestaurantSettings(true, true, 0, 0, false));
        when(repository.countRecentTableQrOrders(eq("tbl-1"), any())).thenReturn(3L);
        when(repository.save(any(OrderEntity.class))).thenAnswer(inv -> {
            OrderEntity e = inv.getArgument(0);
            e.setId("ord-3");
            return e;
        });

        Order created = service.createOrderForTable(List.of(), 103, "tbl-1", "en", "Guest");

        assertThat(created.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(created.cancelReason()).isEqualTo(Order.NEEDS_APPROVAL_TOO_MANY_ORDERS);
        verify(tableService).assignOrder("tbl-1", "ord-3");
    }

    @Test
    void setTableStatus_available_clearsQrTrustedOverride() {
        Table table = new Table("tbl-1", "Table 1", 4, es.brasatech.fastbite.domain.table.TableStatus.OCCUPIED, true,
                List.of("ord-1"), true, "staff", java.time.LocalDateTime.now());
        when(tableService.findById("tbl-1")).thenReturn(Optional.of(table));

        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setId("ord-1");
        orderEntity.setStatus(OrderStatus.DELIVERED);
        when(repository.findById("ord-1")).thenReturn(Optional.of(orderEntity));

        service.setTableStatus("tbl-1", es.brasatech.fastbite.domain.table.TableStatus.AVAILABLE);

        verify(tableService).update(eq("tbl-1"), argThat(t ->
                t.status() == es.brasatech.fastbite.domain.table.TableStatus.AVAILABLE
                && t.orderIds().isEmpty()
                && t.qrTrusted() == null
                && t.qrTrustedBy() == null
                && t.qrTrustedAt() == null));
    }

    @Test
    void findById_withDefaultLanguage_shouldReturnDirectly() {
        // Given
        Order order = new Order(
                List.of(new CartItem("item1", "prod1", "Burger", "Delicious", "img.jpg",
                        1, List.of(new ProductCustomizer(OPTION_ID, "Cheese", BigDecimal.valueOf(1.5), 1)),
                        BigDecimal.TEN)),
                1, OrderPaymentStatus.PAID, OrderChannel.WAITER, DEFAULT_LANG
        );
        OrderEntity entity = createOrderEntity(order);
        when(repository.findForUpdate("order-1")).thenReturn(Optional.of(entity));

        // When
        Optional<Order> result = service.findById("order-1");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().orderLanguage()).isEqualTo(DEFAULT_LANG);
        assertThat(result.get().items().get(0).customizations().get(0).name()).isEqualTo("Cheese");

        verify(optionRepository, never()).findById(any());
    }

    @Test
    void findById_withNonDefaultLanguage_shouldTranslateFromDefaultLanguage() {
        // Given - order stored in default language (English)
        Order order = new Order(
                List.of(new CartItem("item1", "prod1", "Burger", "Delicious", "img.jpg",
                        1, List.of(new ProductCustomizer(OPTION_ID, "Cheese", BigDecimal.valueOf(1.5), 1)),
                        BigDecimal.TEN)),
                1, OrderPaymentStatus.PAID, OrderChannel.WAITER, "es"
        );
        OrderEntity entity = createOrderEntity(order);
        when(repository.findForUpdate("order-1")).thenReturn(Optional.of(entity));

        when(optionRepository.findById(OPTION_ID)).thenReturn(Optional.of(option("Cheese", Map.of("es", Map.of("name", "Queso")))));

        // When
        Optional<Order> result = service.findById("order-1");

        // Then - verify Spanish translation was applied
        assertThat(result).isPresent();
        assertThat(result.get().items().get(0).customizations().get(0).name()).isEqualTo("Queso");

        verify(optionRepository).findById(OPTION_ID);
    }

    @Test
    void findById_withNonDefaultLanguage_noTranslation_shouldUseFallback() {
        // Given - order stored in default language, but translation doesn't exist
        Order order = new Order(
                List.of(new CartItem("item1", "prod1", "Burger", "Delicious", "img.jpg",
                        1, List.of(new ProductCustomizer(OPTION_ID, "Cheese", BigDecimal.valueOf(1.5), 1)),
                        BigDecimal.TEN)),
                1, OrderPaymentStatus.PAID, OrderChannel.WAITER, "pt"
        );
        OrderEntity entity = createOrderEntity(order);
        when(repository.findForUpdate("order-1")).thenReturn(Optional.of(entity));

        // No Portuguese translation on the option
        when(optionRepository.findById(OPTION_ID)).thenReturn(Optional.of(option("Cheese", Map.of("es", Map.of("name", "Queso")))));

        // When
        Optional<Order> result = service.findById("order-1");

        // Then - verify fallback to default language
        assertThat(result).isPresent();
        assertThat(result.get().items().get(0).customizations().get(0).name()).isEqualTo("Cheese");

        verify(optionRepository).findById(OPTION_ID);
    }

    @Test
    void findAll_shouldTranslateAllOrders() {
        // Given - two orders, one in default language, one in Spanish
        Order order1 = new Order(
                List.of(new CartItem("item1", "prod1", "Burger", "Delicious", "img.jpg",
                        1, List.of(new ProductCustomizer(OPTION_ID, "Cheese", BigDecimal.valueOf(1.5), 1)),
                        BigDecimal.TEN)),
                1, OrderPaymentStatus.PAID, OrderChannel.WAITER, DEFAULT_LANG
        );
        Order order2 = new Order(
                List.of(new CartItem("item2", "prod2", "Pizza", "Tasty", "img2.jpg",
                        1, List.of(new ProductCustomizer(OPTION_ID, "Cheese", BigDecimal.valueOf(1.5), 1)),
                        BigDecimal.valueOf(15))),
                2, OrderPaymentStatus.PAID, OrderChannel.ONLINE, "es"
        );

        OrderEntity entity1 = createOrderEntity(order1);
        OrderEntity entity2 = createOrderEntity(order2);
        when(repository.findAll()).thenReturn(List.of(entity1, entity2));

        when(optionRepository.findById(OPTION_ID)).thenReturn(Optional.of(option("Cheese", Map.of("es", Map.of("name", "Queso")))));

        // When
        List<Order> result = service.findAll();

        // Then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).items().get(0).customizations().get(0).name()).isEqualTo("Cheese");
        assertThat(result.get(1).items().get(0).customizations().get(0).name()).isEqualTo("Queso");

        verify(optionRepository).findById(OPTION_ID);
    }

    @Test
    void delete_existingOrder_shouldReturnTrue() {
        // Given
        when(repository.existsById("order-1")).thenReturn(true);

        // When
        boolean result = service.delete("order-1");

        // Then
        assertThat(result).isTrue();
        verify(repository).deleteById("order-1");
    }

    @Test
    void delete_nonExistingOrder_shouldReturnFalse() {
        // Given
        when(repository.existsById("order-1")).thenReturn(false);

        // When
        boolean result = service.delete("order-1");

        // Then
        assertThat(result).isFalse();
        verify(repository, never()).deleteById(any());
    }

    @Test
    void markOrderPaid_onDeliveredOrder_completesIt() {
        OrderEntity entity = new OrderEntity();
        entity.setId("o1");
        entity.setStatus(es.brasatech.fastbite.domain.order.OrderStatus.DELIVERED);
        entity.setPaymentStatus(OrderPaymentStatus.UNPAID);
        entity.setOrderLanguage(DEFAULT_LANG);
        when(repository.findForUpdate("o1")).thenReturn(Optional.of(entity));
        when(tableService.findTableByOrderId("o1")).thenReturn(Optional.empty());

        service.markOrderPaid("o1");

        assertThat(entity.getStatus()).isEqualTo(es.brasatech.fastbite.domain.order.OrderStatus.COMPLETE);
        assertThat(entity.getPaymentStatus()).isEqualTo(OrderPaymentStatus.PAID);
        verify(eventPublisher).publishEvent(any(es.brasatech.fastbite.domain.event.OrderPaymentStatusChangedEvent.class));
        verify(eventPublisher).publishEvent(any(es.brasatech.fastbite.domain.event.OrderStatusChangedEvent.class));
    }

    @Test
    void markOrderPaid_onAcceptedOrder_keepsItAccepted() {
        OrderEntity entity = new OrderEntity();
        entity.setId("o2");
        entity.setStatus(es.brasatech.fastbite.domain.order.OrderStatus.ACCEPTED);
        entity.setPaymentStatus(OrderPaymentStatus.UNPAID);
        entity.setOrderLanguage(DEFAULT_LANG);
        when(repository.findForUpdate("o2")).thenReturn(Optional.of(entity));
        when(tableService.findTableByOrderId("o2")).thenReturn(Optional.empty());

        service.markOrderPaid("o2");

        assertThat(entity.getStatus()).isEqualTo(es.brasatech.fastbite.domain.order.OrderStatus.ACCEPTED);
        assertThat(entity.getPaymentStatus()).isEqualTo(OrderPaymentStatus.PAID);
        verify(eventPublisher).publishEvent(any(es.brasatech.fastbite.domain.event.OrderPaymentStatusChangedEvent.class));
        verify(eventPublisher).publishEvent(any(es.brasatech.fastbite.domain.event.OrderStatusChangedEvent.class));
    }

    @Test
    void markOrderUnpaid_onCompleteOrder_revertsToDelivered() {
        OrderEntity entity = new OrderEntity();
        entity.setId("o3");
        entity.setStatus(es.brasatech.fastbite.domain.order.OrderStatus.COMPLETE);
        entity.setPaymentStatus(OrderPaymentStatus.PAID);
        entity.setOrderLanguage(DEFAULT_LANG);
        when(repository.findForUpdate("o3")).thenReturn(Optional.of(entity));

        service.markOrderUnpaid("o3");

        assertThat(entity.getStatus()).isEqualTo(es.brasatech.fastbite.domain.order.OrderStatus.DELIVERED);
        assertThat(entity.getPaymentStatus()).isEqualTo(OrderPaymentStatus.UNPAID);
        verify(eventPublisher).publishEvent(any(es.brasatech.fastbite.domain.event.OrderPaymentStatusChangedEvent.class));
        verify(eventPublisher).publishEvent(any(es.brasatech.fastbite.domain.event.OrderStatusChangedEvent.class));
    }

    @Test
    void markOrderRefunded_onCompleteOrder_leavesItComplete() {
        OrderEntity entity = new OrderEntity();
        entity.setId("o4");
        entity.setStatus(es.brasatech.fastbite.domain.order.OrderStatus.COMPLETE);
        entity.setPaymentStatus(OrderPaymentStatus.PAID);
        entity.setOrderLanguage(DEFAULT_LANG);
        when(repository.findForUpdate("o4")).thenReturn(Optional.of(entity));

        service.markOrderRefunded("o4");

        assertThat(entity.getStatus()).isEqualTo(es.brasatech.fastbite.domain.order.OrderStatus.COMPLETE);
        assertThat(entity.getPaymentStatus()).isEqualTo(OrderPaymentStatus.REFUNDED);
        verify(eventPublisher).publishEvent(any(es.brasatech.fastbite.domain.event.OrderPaymentStatusChangedEvent.class));
    }

    private OrderEntity createOrderEntity(Order order) {
        OrderEntity entity = new OrderEntity();
        entity.setId(order.id());
        entity.setOrderNumber(order.orderNumber());
        entity.setCreatedAt(order.createdAt());
        entity.setUpdatedAt(order.updatedAt());
        entity.setStatus(order.status());
        entity.setTotal(order.total());
        entity.setCancelReason(order.cancelReason());
        entity.setPaymentStatus(order.paymentStatus());
        entity.setOrderChannel(order.orderChannel());
        entity.setOrderLanguage(order.orderLanguage());

        if (order.items() != null) {
            List<CartItemEntity> itemEntities = order.items().stream()
                    .map(item -> {
                        CartItemEntity itemEntity = new CartItemEntity();
                        itemEntity.setId(item.id());
                        itemEntity.setItemId(item.itemId());
                        itemEntity.setName(item.name());
                        itemEntity.setDescription(item.description());
                        itemEntity.setImage(item.image());
                        itemEntity.setQuantity(item.quantity());
                        itemEntity.setPrice(item.price());

                        if (item.customizations() != null) {
                            List<ProductCustomizerEntity> customizerEntities = item.customizations().stream()
                                    .map(customizer -> {
                                        ProductCustomizerEntity customizerEntity = new ProductCustomizerEntity();
                                        customizerEntity.setId(customizer.id());
                                        customizerEntity.setName(customizer.name());
                                        customizerEntity.setPrice(customizer.price());
                                        customizerEntity.setQuantity(customizer.quantity());
                                        return customizerEntity;
                                    })
                                    .toList();
                            itemEntity.setCustomizations(new ArrayList<>(customizerEntities));
                        }

                        return itemEntity;
                    })
                    .toList();
            entity.setItems(new ArrayList<>(itemEntities));
        }

        return entity;
    }

    private static CustomizationOptionEntity option(String name, Map<String, Map<String, String>> translations) {
        CustomizationOptionEntity option = new CustomizationOptionEntity();
        option.setId(OPTION_ID);
        option.setName(name);
        option.setTranslations(translations);
        return option;
    }
}
