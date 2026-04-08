package com.springairag.core.repository;

import com.springairag.core.entity.RagSilenceSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Silence Schedule Repository
 */
@Repository
public interface RagSilenceScheduleRepository extends JpaRepository<RagSilenceSchedule, Long> {

    Optional<RagSilenceSchedule> findByName(String name);

    List<RagSilenceSchedule> findByEnabledTrue();

    List<RagSilenceSchedule> findByAlertKey(String alertKey);

    List<RagSilenceSchedule> findByAlertKeyAndEnabledTrue(String alertKey);

    void deleteByName(String name);
}
