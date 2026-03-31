package com.springairag.core.extension;

import com.springairag.api.service.DomainRagExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 领域扩展注册表
 *
 * <p>收集所有 {@link DomainRagExtension} 实现，提供按 domainId 查找的能力。
 * 通过构造函数注入自动发现所有 Spring Bean 实现。
 */
@Component
public class DomainExtensionRegistry {

    private static final Logger log = LoggerFactory.getLogger(DomainExtensionRegistry.class);

    private final Map<String, DomainRagExtension> extensions;
    private final DomainRagExtension defaultExtension;

    public DomainExtensionRegistry(List<DomainRagExtension> extensionList) {
        Map<String, DomainRagExtension> map = new ConcurrentHashMap<>();
        DomainRagExtension defaultExt = null;

        if (extensionList != null) {
            for (DomainRagExtension ext : extensionList) {
                String domainId = ext.getDomainId();
                if (domainId == null || domainId.isBlank()) {
                    log.warn("DomainRagExtension [{}] has empty domainId, skipping", ext.getClass().getSimpleName());
                    continue;
                }
                if (map.containsKey(domainId)) {
                    log.warn("Duplicate domainId [{}], overriding with {}", domainId, ext.getClass().getSimpleName());
                }
                map.put(domainId, ext);
                log.info("Registered domain extension: {} -> {}", domainId, ext.getDomainName());

                // 第一个注册的作为默认
                if (defaultExt == null) {
                    defaultExt = ext;
                }
            }
        }

        this.extensions = Collections.unmodifiableMap(map);
        this.defaultExtension = defaultExt;

        log.info("DomainExtensionRegistry initialized with {} extensions", extensions.size());
    }

    /**
     * 根据 domainId 获取领域扩展
     *
     * @param domainId 领域标识
     * @return 扩展实现，未找到返回 null
     */
    public DomainRagExtension getExtension(String domainId) {
        if (domainId == null || domainId.isBlank()) {
            return defaultExtension;
        }
        return extensions.get(domainId);
    }

    /**
     * 获取所有已注册的领域扩展
     */
    public Collection<DomainRagExtension> getAllExtensions() {
        return extensions.values();
    }

    /**
     * 获取领域扩展的系统提示词模板
     *
     * @param domainId 领域标识（null 则使用默认）
     * @return 系统提示词模板，无扩展时返回 null
     */
    public String getSystemPromptTemplate(String domainId) {
        DomainRagExtension ext = getExtension(domainId);
        return ext != null ? ext.getSystemPromptTemplate() : null;
    }

    /**
     * 检查是否有注册的扩展
     */
    public boolean hasExtensions() {
        return !extensions.isEmpty();
    }

    /**
     * 检查指定 domainId 是否存在
     */
    public boolean hasDomain(String domainId) {
        return domainId != null && extensions.containsKey(domainId);
    }
}
