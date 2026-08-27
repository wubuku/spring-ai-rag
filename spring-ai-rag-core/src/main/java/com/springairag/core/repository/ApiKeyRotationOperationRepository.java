package com.springairag.core.repository;

import com.springairag.core.entity.ApiKeyRotationOperation;
import com.springairag.core.entity.ApiKeyRotationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRotationOperationRepository
        extends JpaRepository<ApiKeyRotationOperation, UUID> {

    Optional<ApiKeyRotationOperation> findByPrincipalIdAndIdempotencyKeyHash(
            String principalId, String idempotencyKeyHash);

    Optional<ApiKeyRotationOperation> findByPrincipalIdAndStatus(
            String principalId, ApiKeyRotationStatus status);

    @Query("SELECT operation.rotationId FROM ApiKeyRotationOperation operation "
            + "WHERE operation.status = :status AND operation.expiresAt <= :now "
            + "ORDER BY operation.expiresAt, operation.rotationId")
    List<UUID> findExpiredRotationIds(
            @Param("status") ApiKeyRotationStatus status,
            @Param("now") LocalDateTime now,
            Pageable pageable);

    @Modifying
    @Query(value = "DELETE FROM rag_api_key_rotation "
            + "WHERE rotation_id IN (SELECT rotation_id FROM rag_api_key_rotation "
            + "WHERE status <> 'PENDING' AND terminal_at < :cutoff "
            + "ORDER BY terminal_at, rotation_id LIMIT :batchSize)",
            nativeQuery = true)
    int deleteTerminalBefore(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("batchSize") int batchSize);
}
