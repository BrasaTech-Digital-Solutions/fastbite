package es.brasatech.fastbite.jpa.customization;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for Customization entities.
 */
@Repository
public interface CustomizationJpaRepository extends JpaRepository<CustomizationEntity, String> {
}
