package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.discount.DiscountService;
import es.brasatech.fastbite.application.office.CustomizationService;
import es.brasatech.fastbite.application.office.GroupService;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.application.payment.PaymentService;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.domain.customization.CustomizationDto;
import es.brasatech.fastbite.domain.customization.CustomizationOptionDto;
import es.brasatech.fastbite.domain.discount.DiscountRule;
import es.brasatech.fastbite.domain.discount.DiscountScope;
import es.brasatech.fastbite.domain.discount.DiscountType;
import es.brasatech.fastbite.domain.group.Group;
import es.brasatech.fastbite.domain.payment.PaymentConfig;
import es.brasatech.fastbite.domain.product.ProductDto;
import es.brasatech.fastbite.domain.table.Table;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.TestConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import es.brasatech.fastbite.application.office.I18nConfig;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.table.TableSignatureUtil;
import es.brasatech.fastbite.jpa.product.ProductServiceJpaImpl;
import es.brasatech.fastbite.jpa.group.GroupServiceJpaImpl;
import es.brasatech.fastbite.jpa.customization.CustomizationServiceJpaImpl;
import es.brasatech.fastbite.jpa.table.TableServiceJpaImpl;
import es.brasatech.fastbite.jpa.discount.DiscountServiceJpaImpl;
import es.brasatech.fastbite.jpa.payment.PaymentServiceJpaImpl;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** IDs from another real tenant schema must be invisible to back-office lookups. */
@SpringBootTest(classes = TestConfig.class)
@Import({ProductServiceJpaImpl.class, GroupServiceJpaImpl.class, CustomizationServiceJpaImpl.class,
        TableServiceJpaImpl.class, DiscountServiceJpaImpl.class, PaymentServiceJpaImpl.class})
class BackOfficeTenantIsolationIntegrationTest {
    @MockitoBean I18nConfig i18n;
    @MockitoBean OrderService orderService;
    @MockitoBean TableSignatureUtil signatures;
    @Autowired TenantProvisionerAdapter provisioner;
    @Autowired ProductService products;
    @Autowired GroupService groups;
    @Autowired CustomizationService customizations;
    @Autowired TableService tables;
    @Autowired DiscountService discounts;
    @Autowired PaymentService payments;

    @AfterEach void cleanContext() { TenantContext.clear(); }

    @Test void anotherLocationsIdsAreMissing() {
        when(i18n.getDefaultLanguage()).thenReturn("en");
        provisioner.provisionTenant("bonotfounda");
        provisioner.provisionTenant("bonotfoundb");
        TenantContext.setCurrentTenant("bonotfoundb");
        String product = products.create(new ProductDto(null, "Tea", BigDecimal.ONE, "", "", Set.of(), true)).id();
        String group = groups.create(new Group(null, "Drinks", "", "", List.of())).id();
        String customization = customizations.create(new CustomizationDto(null, "Milk", "radio", List.of(
                new CustomizationOptionDto(null, "Oat", BigDecimal.ZERO)), 0)).id();
        String table = tables.create(new Table("Table A", 2)).id();
        String discount = discounts.create(new DiscountRule("Sale", DiscountScope.ORDER,
                DiscountType.FIXED_AMOUNT, BigDecimal.ONE, BigDecimal.ZERO, null)).id();
        String payment = "payment-other-tenant";
        payments.saveConfig(new PaymentConfig(payment, List.of(), List.of(), false));
        assertThat(products.findById(product)).isPresent();
        assertThat(groups.findById(group)).isPresent();
        assertThat(customizations.findById(customization)).isPresent();
        assertThat(tables.findById(table)).isPresent();
        assertThat(discounts.findById(discount)).isPresent();
        assertThat(payments.findConfigById(payment)).isPresent();

        TenantContext.setCurrentTenant("bonotfounda");
        assertThat(products.findById(product)).isEmpty();
        assertThat(groups.findById(group)).isEmpty();
        assertThat(customizations.findById(customization)).isEmpty();
        assertThat(tables.findById(table)).isEmpty();
        assertThat(discounts.findById(discount)).isEmpty();
        assertThat(payments.findConfigById(payment)).isEmpty();
        assertThat(products.delete(product)).isFalse();
        assertThat(groups.delete(group)).isFalse();
        assertThat(customizations.delete(customization)).isFalse();
        assertThat(tables.delete(table)).isFalse();
        assertThat(discounts.delete(discount)).isFalse();

        TenantContext.setCurrentTenant("bonotfoundb");
        assertThat(products.findById(product)).isPresent();
        assertThat(groups.findById(group)).isPresent();
        assertThat(customizations.findById(customization)).isPresent();
        assertThat(tables.findById(table)).isPresent();
        assertThat(discounts.findById(discount)).isPresent();
        assertThat(payments.findConfigById(payment)).isPresent();
    }
}
