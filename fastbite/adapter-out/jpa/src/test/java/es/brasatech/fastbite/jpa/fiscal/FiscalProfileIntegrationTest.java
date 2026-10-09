package es.brasatech.fastbite.jpa.fiscal;

import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.office.I18nConfig;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.product.ProductDto;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.TestConfig;
import es.brasatech.fastbite.jpa.product.ProductJpaRepository;
import es.brasatech.fastbite.jpa.product.ProductServiceJpaImpl;
import es.brasatech.fastbite.jpa.tenant.TenantBackupRestoreAdapter;
import es.brasatech.fastbite.jpa.tenant.TenantProvisionerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TestConfig.class, properties = "image.upload.directory=target/fiscal-profile-media")
@ActiveProfiles("jpa")
class FiscalProfileIntegrationTest {

    @Autowired FiscalProfileService profiles;
    @Autowired ProductJpaRepository productRepository;
    @Autowired TenantProvisionerAdapter provisioner;
    @Autowired TenantBackupRestoreAdapter backups;
    @Autowired TransactionTemplate transactions;
    ProductService products;

    @org.junit.jupiter.api.BeforeEach void products() {
        products = new ProductServiceJpaImpl(productRepository, new I18nConfig());
    }

    private static final FiscalProfile ANA = new FiscalProfile("Ana García López", "Kebab House", "12345678Z",
            "Calle Mayor 12", "28013", "Madrid", "Madrid", null, null, "KH", new BigDecimal("10"));

    @AfterEach void clear() { TenantContext.clear(); }

    private String tenant() {
        String tenant = "fiscal" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        provisioner.provisionTenant(tenant);
        return tenant;
    }

    private <T> T in(String tenant, Supplier<T> work) {
        TenantContext.setCurrentTenant(tenant);
        try { return transactions.execute(status -> work.get()); } finally { TenantContext.clear(); }
    }

    @Test
    void eachLocationKeepsItsOwnBusinessDetails() {
        String centre = tenant();
        String airport = tenant();
        var company = new FiscalProfile("Aeropuerto Food SL", null, "B12345674", "Terminal 4", "28042", "Madrid",
                "Madrid", null, null, "AER", new BigDecimal("10"));

        in(centre, () -> profiles.save(ANA));
        in(airport, () -> profiles.save(company));

        assertThat(in(centre, profiles::get)).isEqualTo(ANA);
        assertThat(in(airport, profiles::get).taxId()).isEqualTo("B12345674");
    }

    @Test
    void aNewLocationHasNoDetailsYetAndChargesTheReducedRate() {
        String fresh = tenant();

        var profile = in(fresh, profiles::get);

        assertThat(profile).isEqualTo(FiscalProfile.EMPTY);
        assertThat(profile.defaultVatRate()).isEqualByComparingTo("10");
    }

    @Test
    void productsKeepTheirOwnVatRateAndOnlySpanishRatesAreAccepted() {
        String tenant = tenant();

        var beer = in(tenant, () -> products.create(new ProductDto(null, "Beer to go", new BigDecimal("2.50"), "Can",
                null, Set.of(), true, new BigDecimal("21"))));
        var kebab = in(tenant, () -> products.create(new ProductDto(null, "Kebab", new BigDecimal("7.50"), "Beef",
                null, Set.of(), true)));

        assertThat(in(tenant, () -> products.findById(beer.id())).orElseThrow().vatRate()).isEqualByComparingTo("21");
        assertThat(in(tenant, () -> products.findById(kebab.id())).orElseThrow().vatRate()).isNull();
        assertThatThrownBy(() -> in(tenant, () -> products.create(new ProductDto(null, "Odd", BigDecimal.ONE, "",
                null, Set.of(), true, new BigDecimal("7"))))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void takingASoldOutProductOffSaleChangesNothingElse() {
        String tenant = tenant();
        var kebab = in(tenant, () -> products.create(new ProductDto(null, "Kebab", new BigDecimal("7.50"), "Beef",
                null, Set.of(), true, new BigDecimal("21"))));

        var soldOut = in(tenant, () -> products.setActive(kebab.id(), false)).orElseThrow();

        assertThat(soldOut.active()).isFalse();
        assertThat(soldOut.name()).isEqualTo("Kebab");
        assertThat(soldOut.vatRate()).isEqualByComparingTo("21");
    }

    @Test
    void aBackupCarriesTheBusinessDetailsToAnotherLocation() {
        String source = tenant();
        String target = tenant();
        in(source, () -> profiles.save(ANA));
        var archive = new ByteArrayOutputStream();
        backups.exportBackup(source, archive);

        backups.importRestore(target, new ByteArrayInputStream(archive.toByteArray()));

        assertThat(in(target, profiles::get)).isEqualTo(ANA);
    }
}
