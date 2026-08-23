package com.springairag.core.repository;

import com.springairag.core.entity.RagApiPrincipal;
import com.springairag.core.entity.ApiKeyRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RagApiPrincipalRepository extends JpaRepository<RagApiPrincipal, String> {

    Optional<RagApiPrincipal> findByPrincipalId(String principalId);

    List<RagApiPrincipal> findAllByOrderByCreatedAtDesc();

    @Query("SELECT COUNT(p) FROM RagApiPrincipal p WHERE p.role = :role "
            + "AND p.revokedAt IS NULL "
            + "AND (p.expiresAt IS NULL OR p.expiresAt > :now)")
    long countUsableByRole(@Param("role") ApiKeyRole role,
                           @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RagApiPrincipal p SET p.updatedAt = p.updatedAt WHERE p.principalId = :principalId")
    int acquireManagementWrite(@Param("principalId") String principalId);

    @Modifying
    @Query("UPDATE RagApiPrincipal p SET p.lastUsedAt = :usedAt, p.updatedAt = :usedAt "
            + "WHERE p.principalId = :principalId "
            + "AND (p.lastUsedAt IS NULL OR p.lastUsedAt < :threshold)")
    int touchLastUsedIfOlder(@Param("principalId") String principalId,
                             @Param("usedAt") LocalDateTime usedAt,
                             @Param("threshold") LocalDateTime threshold);
}
