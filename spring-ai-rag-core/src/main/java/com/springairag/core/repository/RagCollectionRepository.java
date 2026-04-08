package com.springairag.core.repository;

import com.springairag.core.entity.RagCollection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * RAG Document Collection JPA Repository
 */
@Repository
public interface RagCollectionRepository extends JpaRepository<RagCollection, Long> {

    /**
     * Comprehensive search: name fuzzy match + enabled status filter (excludes deleted by default).
     */
    @Query("SELECT c FROM RagCollection c WHERE c.deleted = false AND " +
           "(COALESCE(:name, '') = '' OR LOWER(c.name) LIKE LOWER(CONCAT('%', :name, '%'))) AND " +
           "(:enabled IS NULL OR c.enabled = :enabled)")
    Page<RagCollection> searchCollections(@Param("name") String name,
                                           @Param("enabled") Boolean enabled,
                                           Pageable pageable);

    /**
     * Find by ID (excluding deleted).
     */
    Optional<RagCollection> findByIdAndDeletedFalse(Long id);

    /**
     * Soft delete: mark as deleted.
     */
    @Modifying
    @Query("UPDATE RagCollection c SET c.deleted = true, c.deletedAt = :deletedAt WHERE c.id = :id")
    int softDelete(@Param("id") Long id, @Param("deletedAt") LocalDateTime deletedAt);

    /**
     * Restore: clear the deleted flag.
     */
    @Modifying
    @Query("UPDATE RagCollection c SET c.deleted = false, c.deletedAt = null WHERE c.id = :id")
    int restore(@Param("id") Long id);

    /**
     * Find by name.
     */
    RagCollection findByName(String name);
}
