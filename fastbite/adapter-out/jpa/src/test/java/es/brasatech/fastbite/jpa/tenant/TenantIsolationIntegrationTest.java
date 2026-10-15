package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.jpa.product.ProductEntity;
import es.brasatech.fastbite.jpa.product.ProductJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = es.brasatech.fastbite.jpa.TestConfig.class)
class TenantIsolationIntegrationTest {

    @Autowired
    private ProductJpaRepository productRepository;

    @Autowired
    private TenantProvisionerAdapter tenantProvisionerAdapter;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void testTenantDataIsolation() {
        tenantProvisionerAdapter.provisionTenant("1");
        tenantProvisionerAdapter.provisionTenant("2");

        // Save product in Tenant 1
        TenantContext.setCurrentTenant("1");
        String id1 = transactionTemplate.execute(status -> {
            ProductEntity p1 = new ProductEntity();
            p1.setName("Tenant 1 Burger");
            p1.setPrice(BigDecimal.valueOf(9.99));
            p1.setActive(true);
            ProductEntity saved = productRepository.save(p1);
            return saved.getId();
        });

        // Save product in Tenant 2
        TenantContext.setCurrentTenant("2");
        String id2 = transactionTemplate.execute(status -> {
            ProductEntity p2 = new ProductEntity();
            p2.setName("Tenant 2 Pizza");
            p2.setPrice(BigDecimal.valueOf(14.99));
            p2.setActive(true);
            ProductEntity saved = productRepository.save(p2);
            return saved.getId();
        });

        // Query Tenant 1 products
        TenantContext.setCurrentTenant("1");
        transactionTemplate.execute(status -> {
            List<ProductEntity> list1 = productRepository.findAll();
            assertEquals(1, list1.size());
            assertEquals("Tenant 1 Burger", list1.get(0).getName());
            assertEquals(id1, list1.get(0).getId());
            return null;
        });

        // Query Tenant 2 products
        TenantContext.setCurrentTenant("2");
        transactionTemplate.execute(status -> {
            List<ProductEntity> list2 = productRepository.findAll();
            assertEquals(1, list2.size());
            assertEquals("Tenant 2 Pizza", list2.get(0).getName());
            assertEquals(id2, list2.get(0).getId());
            return null;
        });
    }
}
