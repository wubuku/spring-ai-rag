package com.springairag.core.repository;

import com.springairag.core.entity.RagClientError;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
}
