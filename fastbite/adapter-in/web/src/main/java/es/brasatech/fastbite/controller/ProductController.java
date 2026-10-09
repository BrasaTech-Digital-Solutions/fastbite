package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.domain.product.ProductDto;
import es.brasatech.fastbite.dto.office.BackOfficeDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/api/backoffice/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    // ===== PRODUCT ENDPOINTS =====

    /**
     * Get all products
     */
    @GetMapping
    @ResponseBody
    public ResponseEntity<List<BackOfficeDto<ProductDto>>> getAllProducts() {
        List<ProductDto> products = productService.findAll();
        List<BackOfficeDto<ProductDto>> response = products.stream()
                .map(product -> BackOfficeDto.of(product.id(), product))
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Get product by ID
     */
    @GetMapping("/{id}")
    @ResponseBody
    public ResponseEntity<BackOfficeDto<ProductDto>> getProductById(@PathVariable String id) {
        return productService.findById(id)
                .map(product -> BackOfficeDto.of(product.id(), product))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new product
     */
    @PostMapping
    @ResponseBody
    public ResponseEntity<BackOfficeDto<ProductDto>> createProduct(@RequestBody ProductDto productDto) {
        ProductDto created = productService.create(productDto);
        BackOfficeDto<ProductDto> response = BackOfficeDto.of(created.id(), created);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Update an existing product
     */
    @PutMapping("/{id}")
    @ResponseBody
    public ResponseEntity<BackOfficeDto<ProductDto>> updateProduct(
            @PathVariable String id,
            @RequestBody ProductDto productDto) {
        return productService.update(id, productDto)
                .map(product -> BackOfficeDto.of(product.id(), product))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete a product
     */
    @DeleteMapping("/{id}")
    @ResponseBody
    public ResponseEntity<Void> deleteProduct(@PathVariable String id) {
        if (productService.delete(id)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Toggle product 86 / active stock status
     */
    @PostMapping("/{id}/toggle-86")
    @ResponseBody
    public ResponseEntity<BackOfficeDto<ProductDto>> toggleProductStock(@PathVariable String id) {
        // Only the flag changes: saving the whole product would store the names of the staff's language
        return productService.findById(id)
                .flatMap(product -> productService.setActive(id, !product.active()))
                .map(product -> BackOfficeDto.of(product.id(), product))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
