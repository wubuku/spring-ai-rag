package com.springairag.core.repository;

import com.springairag.core.entity.RagSloConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * SLO 配置仓库
 */
@Repository
public interface SloConfigRepository extends JpaRepository<RagSloConfig, Long> {

    /**
     * 按名称查找 SLO 配置
     */
    Optional<RagSloConfig> findBySloName(String sloName);

    /**
     * 获取所有启用的 SLO 配置
     */
    List<RagSloConfig> findByEnabledTrue();

    /**
     * 按类型查找 SLO 配置
     */
    List<RagSloConfig> findBySloType(String sloType);

    /**
     * 按名称删除
     */
    void deleteBySloName(String sloName);
}
