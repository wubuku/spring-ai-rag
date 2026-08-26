package com.springairag.core.repository;

import com.springairag.core.entity.CollectionProvisioningOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface CollectionProvisioningOperationRepository
        extends JpaRepository<CollectionProvisioningOperation, Long> {

    Optional<CollectionProvisioningOperation> findByOwnerIdAndIdempotencyKeyHash(
            String ownerId, String idempotencyKeyHash);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM rag_collection_provisioning_operation "
            + "WHERE id IN (SELECT id FROM rag_collection_provisioning_operation "
            + "WHERE completed_at < :cutoff ORDER BY id LIMIT :batchSize)",
            nativeQuery = true)
    int deleteCompletedBefore(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("batchSize") int batchSize);
}
