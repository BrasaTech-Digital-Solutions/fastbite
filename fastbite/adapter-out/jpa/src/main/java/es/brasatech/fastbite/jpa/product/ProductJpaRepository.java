package es.brasatech.fastbite.jpa.product;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * JPA repository for Product entities.
 */
@Repository
public interface ProductJpaRepository extends JpaRepository<ProductEntity, String> {

    /** Loads each product's customization ids in the same query (otherwise one query per product). */
    @Override
    @EntityGraph(attributePaths = "customizations")
    List<ProductEntity> findAll();
}
