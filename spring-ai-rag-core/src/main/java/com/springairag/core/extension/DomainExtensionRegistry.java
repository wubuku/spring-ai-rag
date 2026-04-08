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
 * Domain Extension Registry
 *
 * <p>Collects all {@link DomainRagExtension} implementations and provides lookup by domainId.
 * Auto-discovers all Spring Bean implementations via constructor injection.
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

                // First registered becomes the default
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
     * Get domain extension by domainId.
     *
     * @param domainId domain identifier
     * @return extension implementation, null if not found
     */
    public DomainRagExtension getExtension(String domainId) {
        if (domainId == null || domainId.isBlank()) {
            return defaultExtension;
        }
        return extensions.get(domainId);
    }

    /**
     * Get all registered domain extensions.
     */
    public Collection<DomainRagExtension> getAllExtensions() {
        return extensions.values();
    }

    /**
     * Get domain extension's system prompt template.
     *
     * @param domainId domain identifier (null to use default)
     * @return system prompt template, null if no extension found
     */
    public String getSystemPromptTemplate(String domainId) {
        DomainRagExtension ext = getExtension(domainId);
        return ext != null ? ext.getSystemPromptTemplate() : null;
    }

    /**
     * Check if any extensions are registered.
     */
    public boolean hasExtensions() {
        return !extensions.isEmpty();
    }

    /**
     * Check if a specific domainId exists.
     */
    public boolean hasDomain(String domainId) {
        return domainId != null && extensions.containsKey(domainId);
    }
}
