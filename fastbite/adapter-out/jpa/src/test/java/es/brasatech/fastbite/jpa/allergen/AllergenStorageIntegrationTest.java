package es.brasatech.fastbite.jpa.allergen;

import es.brasatech.fastbite.application.office.I18nConfig;
import es.brasatech.fastbite.domain.allergen.Allergen;
import es.brasatech.fastbite.domain.customization.CustomizationDto;
import es.brasatech.fastbite.domain.customization.CustomizationOptionDto;
import es.brasatech.fastbite.domain.product.ProductDto;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.TestConfig;
import es.brasatech.fastbite.jpa.customization.CustomizationJpaRepository;
import es.brasatech.fastbite.jpa.customization.CustomizationServiceJpaImpl;
import es.brasatech.fastbite.jpa.product.ProductJpaRepository;
import es.brasatech.fastbite.jpa.product.ProductServiceJpaImpl;
import es.brasatech.fastbite.jpa.tenant.TenantBackupRestoreAdapter;
import es.brasatech.fastbite.jpa.tenant.TenantProvisionerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestConfig.class, properties = "image.upload.directory=target/allergen-media")
@ActiveProfiles("jpa")
class AllergenStorageIntegrationTest {

    @Autowired ProductJpaRepository productRepository;
    @Autowired CustomizationJpaRepository customizationRepository;
    @Autowired TenantProvisionerAdapter provisioner;
    @Autowired TenantBackupRestoreAdapter backups;
    @Autowired TransactionTemplate transactions;
    ProductServiceJpaImpl products;
    CustomizationServiceJpaImpl customizations;

    @BeforeEach void services() {
        products = new ProductServiceJpaImpl(productRepository, new I18nConfig());
        customizations = new CustomizationServiceJpaImpl(customizationRepository, new I18nConfig());
    }

    @AfterEach void clear() { TenantContext.clear(); }

    private String tenant() {
        String tenant = "allergen" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        provisioner.provisionTenant(tenant);
        return tenant;
    }

    private <T> T in(String tenant, Supplier<T> work) {
        TenantContext.setCurrentTenant(tenant);
        try { return transactions.execute(status -> work.get()); } finally { TenantContext.clear(); }
    }

    private static ProductDto dish(String name, Set<Allergen> allergens) {
        return new ProductDto(null, name, new BigDecimal("9.00"), "", null, Set.of(), true, null, allergens);
    }

    @Test
    void notDeclaredNoneAndSomeAllergensAreKeptApart() {
        String tenant = tenant();
        var pizza = in(tenant, () -> products.create(dish("Pizza", Set.of(Allergen.MILK, Allergen.GLUTEN))));
        var water = in(tenant, () -> products.create(dish("Water", Set.of())));
        var special = in(tenant, () -> products.create(dish("Special", null)));

        assertThat(in(tenant, () -> products.findById(pizza.id())).orElseThrow().allergens())
                .containsExactly(Allergen.GLUTEN, Allergen.MILK);
        var none = in(tenant, () -> products.findById(water.id())).orElseThrow();
        assertThat(none.allergens()).isEmpty();
        assertThat(none.allergensDeclared()).isTrue();
        assertThat(in(tenant, () -> products.findById(special.id())).orElseThrow().allergensDeclared()).isFalse();
    }

    @Test
    void optionsKeepTheAllergensTheyAddAcrossEdits() {
        String tenant = tenant();
        var toppings = in(tenant, () -> customizations.create(new CustomizationDto(null, "Toppings", "checkbox", List.of(
                new CustomizationOptionDto(null, "Extra cheese", new BigDecimal("0.50"), false, 0, Set.of(Allergen.MILK)),
                new CustomizationOptionDto(null, "Bacon", new BigDecimal("0.80"), false, 0, Set.of())), 0)));

        var saved = in(tenant, () -> customizations.findById(toppings.id())).orElseThrow();

        assertThat(saved.options().get(0).allergens()).containsExactly(Allergen.MILK);
        assertThat(saved.options().get(1).allergens()).isNull();
    }

    @Test
    void backupsCarryAllergensOfDishesAndOptions() {
        String source = tenant();
        String target = tenant();
        var pizza = in(source, () -> products.create(dish("Pizza", Set.of(Allergen.GLUTEN))));
        var sauce = in(source, () -> customizations.create(new CustomizationDto(null, "Sauce", "radio", List.of(
                new CustomizationOptionDto(null, "Garlic", BigDecimal.ZERO, true, 1, Set.of(Allergen.EGGS))), 0)));
        var archive = new ByteArrayOutputStream();
        backups.exportBackup(source, archive);

        backups.importRestore(target, new ByteArrayInputStream(archive.toByteArray()));

        assertThat(in(target, () -> products.findById(pizza.id())).orElseThrow().allergens()).containsExactly(Allergen.GLUTEN);
        assertThat(in(target, () -> customizations.findById(sauce.id())).orElseThrow().options().getFirst().allergens())
                .containsExactly(Allergen.EGGS);
    }
}
