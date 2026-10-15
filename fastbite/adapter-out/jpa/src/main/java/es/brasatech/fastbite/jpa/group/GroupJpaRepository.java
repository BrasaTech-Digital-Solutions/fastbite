package es.brasatech.fastbite.jpa.group;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * JPA repository for Group entities.
 */
@Repository
public interface GroupJpaRepository extends JpaRepository<GroupEntity, String> {

    /** Loads each group's product ids in the same query (otherwise one query per group). */
    @Override
    @EntityGraph(attributePaths = "products")
    List<GroupEntity> findAll();
}
