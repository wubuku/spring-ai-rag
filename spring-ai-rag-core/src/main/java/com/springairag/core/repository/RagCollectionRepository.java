package com.springairag.core.repository;

import com.springairag.core.entity.RagCollection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
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

    @Query("SELECT c FROM RagCollection c WHERE c.deleted = false AND " +
           "(COALESCE(:name, '') = '' OR LOWER(c.name) LIKE LOWER(CONCAT('%', :name, '%'))) AND " +
           "(COALESCE(:query, '') = '' OR " +
           "LOWER(c.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(c.collectionKey) LIKE LOWER(CONCAT('%', :query, '%'))) AND " +
           "(:enabled IS NULL OR c.enabled = :enabled)")
    Page<RagCollection> searchCollections(@Param("name") String name,
                                           @Param("query") String query,
                                           @Param("enabled") Boolean enabled,
                                           Pageable pageable);

    @Query("SELECT c FROM RagCollection c WHERE c.deleted = false AND c.id IN :ids AND " +
           "(COALESCE(:name, '') = '' OR LOWER(c.name) LIKE LOWER(CONCAT('%', :name, '%'))) AND " +
           "(:enabled IS NULL OR c.enabled = :enabled)")
    Page<RagCollection> searchCollectionsByIds(@Param("ids") List<Long> ids,
                                                @Param("name") String name,
                                                @Param("enabled") Boolean enabled,
                                                Pageable pageable);

    @Query("SELECT c FROM RagCollection c WHERE c.deleted = false AND c.id IN :ids AND " +
           "(COALESCE(:name, '') = '' OR LOWER(c.name) LIKE LOWER(CONCAT('%', :name, '%'))) AND " +
           "(COALESCE(:query, '') = '' OR " +
           "LOWER(c.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(c.collectionKey) LIKE LOWER(CONCAT('%', :query, '%'))) AND " +
           "(:enabled IS NULL OR c.enabled = :enabled)")
    Page<RagCollection> searchCollectionsByIds(@Param("ids") List<Long> ids,
                                                @Param("name") String name,
                                                @Param("query") String query,
                                                @Param("enabled") Boolean enabled,
                                                Pageable pageable);

    /**
     * Find by ID (excluding deleted).
     */
    Optional<RagCollection> findByIdAndDeletedFalse(Long id);

    Optional<RagCollection> findByCollectionKey(String collectionKey);

    Optional<RagCollection> findByCollectionKeyAndDeletedFalse(String collectionKey);

    List<RagCollection> findAllByCollectionKeyInAndDeletedFalse(
            Collection<String> collectionKeys);

    boolean existsByCollectionKey(String collectionKey);

    /**
     * Soft delete: mark as deleted.
     */
    @Modifying
    @Query("UPDATE RagCollection c SET c.deleted = true, c.deletedAt = :deletedAt, "
            + "c.version = c.version + 1 WHERE c.id = :id AND c.deleted = false "
            + "AND c.version = :expectedVersion")
    int softDeleteIfVersion(
            @Param("id") Long id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("deletedAt") LocalDateTime deletedAt);

    /**
     * Advance the lifecycle version only when the caller still owns its read token.
     */
    @Modifying
    @Query("UPDATE RagCollection c SET c.version = c.version + 1 "
            + "WHERE c.id = :id AND c.deleted = false AND c.version = :expectedVersion")
    int advanceActiveVersion(
            @Param("id") Long id,
            @Param("expectedVersion") Long expectedVersion);

    /**
     * Restore: clear the deleted flag.
     */
    @Modifying
    @Query("UPDATE RagCollection c SET c.deleted = false, c.deletedAt = null, "
           + "c.version = c.version + 1 WHERE c.id = :id AND c.deleted = true")
    int restore(@Param("id") Long id);

    /**
     * Find by name.
     */
    RagCollection findByName(String name);
}
