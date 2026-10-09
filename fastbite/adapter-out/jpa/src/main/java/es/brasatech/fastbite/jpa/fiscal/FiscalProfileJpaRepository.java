package es.brasatech.fastbite.jpa.fiscal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FiscalProfileJpaRepository extends JpaRepository<FiscalProfileEntity, Integer> {
}
