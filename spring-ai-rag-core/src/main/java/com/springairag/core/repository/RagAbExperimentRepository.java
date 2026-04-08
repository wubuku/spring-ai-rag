package com.springairag.core.repository;

import com.springairag.core.entity.RagAbExperiment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * A/B Experiment Repository
 */
@Repository
public interface RagAbExperimentRepository extends JpaRepository<RagAbExperiment, Long> {

    Optional<RagAbExperiment> findByExperimentName(String experimentName);

    List<RagAbExperiment> findByStatus(String status);

    @Query("SELECT e FROM RagAbExperiment e WHERE e.status = 'RUNNING' ORDER BY e.createdAt DESC")
    List<RagAbExperiment> findRunningExperiments();

    boolean existsByExperimentName(String experimentName);
}
