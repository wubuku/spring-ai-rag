package com.springairag.core.repository;

import com.springairag.core.entity.RagClientError;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface RagClientErrorRepository extends JpaRepository<RagClientError, Long> {

    Page<RagClientError> findByCreatedAtBetweenOrderByCreatedAtDesc(Instant start, Instant end, Pageable pageable);

    Page<RagClientError> findByErrorTypeOrderByCreatedAtDesc(String errorType, Pageable pageable);

    long countByErrorType(String errorType);

    List<RagClientError> findTop10ByOrderByCreatedAtDesc();

    @Modifying
    @Query(value = """
            DELETE FROM rag_client_error
            WHERE id IN (
                SELECT id FROM rag_client_error
                WHERE created_at < :cutoff
                ORDER BY created_at ASC, id ASC
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteExpiredBatch(
            @Param("cutoff") Instant cutoff,
            @Param("batchSize") int batchSize);
}
