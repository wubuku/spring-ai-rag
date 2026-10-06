package com.springairag.core.repository;

import com.springairag.core.entity.RagAbResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    /**
     * Recorded-result counts for a whole page of experiments, in one query.
     *
     * <p>Batch 932: the experiment list reports a sample count, and the obvious
     * way to get it — one {@code countByExperimentId} per row — is a query per
     * row on a page that asks for a hundred. This groups instead, so the list
     * costs the same two queries whether it shows one experiment or a hundred.
     *
     * <p>An experiment with no results is simply absent from the result, which is
     * why callers must treat "missing" as zero rather than as unknown. That is
     * correct here and not a guess: a result row is what a sample <i>is</i>, so
     * no rows means zero samples, not an unreported number.
     */
    @Query("SELECT r.experiment.id AS experimentId, COUNT(r) AS total "
            + "FROM RagAbResult r WHERE r.experiment.id IN :experimentIds "
            + "GROUP BY r.experiment.id")
    List<ExperimentResultCount> countResultsByExperimentIds(
            @Param("experimentIds") Collection<Long> experimentIds);

    /** One row of {@link #countResultsByExperimentIds}. */
    interface ExperimentResultCount {
        Long getExperimentId();

        long getTotal();
    }
}
