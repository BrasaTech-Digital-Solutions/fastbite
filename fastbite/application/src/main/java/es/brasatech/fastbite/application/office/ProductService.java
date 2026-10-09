package es.brasatech.fastbite.application.office;

import es.brasatech.fastbite.domain.product.ProductDto;

import java.util.List;
import java.util.Optional;

/**
 * Service interface for managing products in the BackOffice system.
 * Implementations can use different persistence strategies (in-memory, MongoDB,
 * JPA).
 */
public interface ProductService {

    /**
     * Get all products
     */
    List<ProductDto> findAll();

    /**
     * Find product by ID
     */
    Optional<ProductDto> findById(String id);

    /**
     * Create a new product
     */
    ProductDto create(ProductDto productDto);

    /**
     * Update an existing product
     */
    Optional<ProductDto> update(String id, ProductDto productDto);

    /**
     * Puts a product on sale or takes it off (sold out), leaving everything else as it is.
     */
    Optional<ProductDto> setActive(String id, boolean active);

    /**
     * Delete a product
     */
    boolean delete(String id);

    /**
     * Clear all products (for testing)
     */
    void clear();
}
