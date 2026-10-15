package es.brasatech.fastbite.jpa.customization;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for CustomizationOption entities.
 * Provides efficient queries for translation lookups in orders.
 */
@Repository
public interface CustomizationOptionJpaRepository extends JpaRepository<CustomizationOptionEntity, String> {

    /**
     * Find all options for a customization (ordered by optionIndex)
     */
    List<CustomizationOptionEntity> findByCustomizationIdOrderByOptionIndexAsc(String customizationId);

    /**
     * Find option by ID (for basic lookups)
     */
    Optional<CustomizationOptionEntity> findById(String id);

    /**
     * Delete all options for a customization (cascade will handle this, but
     * explicit for clarity)
     */
    void deleteByCustomizationId(String customizationId);
}
