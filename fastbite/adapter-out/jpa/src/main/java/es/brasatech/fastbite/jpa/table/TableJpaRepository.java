package es.brasatech.fastbite.jpa.table;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface TableJpaRepository extends JpaRepository<TableEntity, String> {

    @Query("SELECT t.id, t.name, o FROM DiningTable t JOIN t.orderIds o WHERE o IN :orderIds")
    List<Object[]> findTableOrderPairs(@Param("orderIds") Collection<String> orderIds);
}

