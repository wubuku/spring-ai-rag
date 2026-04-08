package com.springairag.core.repository;

import com.springairag.core.entity.RagAbResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * A/B Test Result Repository
 */
@Repository
public interface RagAbResultRepository extends JpaRepository<RagAbResult, Long> {

    List<RagAbResult> findByExperimentId(Long experimentId);

    Page<RagAbResult> findByExperimentIdOrderByCreatedAtDesc(Long experimentId, Pageable pageable);

    List<RagAbResult> findByExperimentIdAndVariantName(Long experimentId, String variantName);

    long countByExperimentIdAndVariantName(Long experimentId, String variantName);

    boolean existsBySessionIdAndExperimentId(String sessionId, Long experimentId);
}
