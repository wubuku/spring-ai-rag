package com.springairag.core.repository;

import com.springairag.core.entity.RagSloConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * SLO Configuration Repository
 */
@Repository
public interface SloConfigRepository extends JpaRepository<RagSloConfig, Long> {

    /**
     * Find SLO config by name.
     */
    Optional<RagSloConfig> findBySloName(String sloName);

    /**
     * Get all enabled SLO configs.
     */
    List<RagSloConfig> findByEnabledTrue();

    /**
     * Find SLO configs by type.
     */
    List<RagSloConfig> findBySloType(String sloType);

    /**
     * Delete by name.
     */
    void deleteBySloName(String sloName);
}
