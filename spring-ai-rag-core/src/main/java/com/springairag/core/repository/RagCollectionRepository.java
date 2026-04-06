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
 * RAG 文档集合 JPA Repository
 */
@Repository
public interface RagCollectionRepository extends JpaRepository<RagCollection, Long> {

    /**
     * 综合搜索：名称模糊 + 启用状态过滤（默认排除已删除）
     */
    @Query("SELECT c FROM RagCollection c WHERE c.deleted = false AND " +
           "(COALESCE(:name, '') = '' OR LOWER(c.name) LIKE LOWER(CONCAT('%', :name, '%'))) AND " +
           "(:enabled IS NULL OR c.enabled = :enabled)")
    Page<RagCollection> searchCollections(@Param("name") String name,
                                           @Param("enabled") Boolean enabled,
                                           Pageable pageable);

    /**
     * 按ID查找（排除已删除）
     */
    Optional<RagCollection> findByIdAndDeletedFalse(Long id);

    /**
     * 软删除：标记为已删除
     */
    @Modifying
    @Query("UPDATE RagCollection c SET c.deleted = true, c.deletedAt = :deletedAt WHERE c.id = :id")
    int softDelete(@Param("id") Long id, @Param("deletedAt") LocalDateTime deletedAt);

    /**
     * 恢复：取消删除标记
     */
    @Modifying
    @Query("UPDATE RagCollection c SET c.deleted = false, c.deletedAt = null WHERE c.id = :id")
    int restore(@Param("id") Long id);

    /**
     * 按名称查找
     */
    RagCollection findByName(String name);
}
