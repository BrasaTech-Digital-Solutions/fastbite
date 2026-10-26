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
class FiscalProfileIntegrationTest {

    @Autowired FiscalProfileService profiles;
    @Autowired FiscalTicketRepositoryJpa tickets;
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

    private static es.brasatech.fastbite.domain.fiscal.FiscalTicket sampleTicket(String orderId) {
        var line = new es.brasatech.fastbite.domain.fiscal.TicketLine("Kebab", 2, new BigDecimal("7.00"), new BigDecimal("14.00"), new BigDecimal("10.00"),
                java.util.List.of(new es.brasatech.fastbite.domain.fiscal.TicketLine.Option("Garlic sauce", new BigDecimal("0.50"), 1)));
        var vat = es.brasatech.fastbite.domain.fiscal.VatBreakdown.of(java.util.List.of(new es.brasatech.fastbite.domain.fiscal.VatBreakdown.Amount(line.total(), line.vatRate())));
        String series = "KH-T26";
        return new es.brasatech.fastbite.domain.fiscal.FiscalTicket(UUID.randomUUID().toString(),
                es.brasatech.fastbite.domain.fiscal.TicketKind.SIMPLIFIED, series, 1, series + "-000001",
                false, java.time.OffsetDateTime.parse("2026-10-09T23:30:00+02:00"), "carla",
                new es.brasatech.fastbite.domain.fiscal.FiscalTicket.Issuer("Ana García", "Kebab House", "12345678Z", "Calle Mayor 12", "28013", "Madrid",
                        "Madrid", "+34 600 000 000"),
                null,
                java.util.List.of(line), BigDecimal.ZERO, line.total(), vat, new BigDecimal("1.40"),
                es.brasatech.fastbite.domain.fiscal.PaymentMethod.CASH,
                new BigDecimal("20.00"), new BigDecimal("4.60"), null, java.util.List.of(orderId), "12", "Terrace 4", null);
    }

    @Test
    void restoringBackupAfterRealTicketLocksTaxIdAndSeriesPrefix() {
        String target = tenant();
        in(target, () -> profiles.save(ANA));
        in(target, () -> tickets.add(sampleTicket("order-1")));

        // Source has different NIF and series
        String source = tenant();
        var company = new FiscalProfile("Aeropuerto Food SL", "Air Kebab", "B12345674", "Terminal 4", "28042", "Madrid",
                "Madrid", null, null, "AER", new BigDecimal("10"));
        in(source, () -> profiles.save(company));
        var archive = new ByteArrayOutputStream();
        backups.exportBackup(source, archive);

        var result = backups.importRestore(target, new ByteArrayInputStream(archive.toByteArray()));
        assertThat(result.fiscalProfileLocked()).isTrue();

        FiscalProfile restored = in(target, profiles::get);
        assertThat(restored.taxId()).isEqualTo("12345678Z");
        assertThat(restored.seriesPrefix()).isEqualTo("KH");
        assertThat(restored.legalName()).isEqualTo("Aeropuerto Food SL");
        assertThat(restored.tradeName()).isEqualTo("Air Kebab");
        assertThat(restored.addressLine()).isEqualTo("Terminal 4");
    }

    @Test
    void cleanTenantDataPreservesFiscalProfileAndTickets() {
        String tenant = tenant();
        in(tenant, () -> profiles.save(ANA));
        in(tenant, () -> tickets.add(sampleTicket("order-1")));

        backups.cleanTenantData(tenant);

        assertThat(in(tenant, profiles::get)).isEqualTo(ANA);
        assertThat(in(tenant, tickets::anyRealTicket)).isTrue();
    }
}
