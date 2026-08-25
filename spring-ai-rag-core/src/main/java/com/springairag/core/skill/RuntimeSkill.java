package com.springairag.core.skill;

import com.springairag.core.resource.ResourceEntry;
import com.springairag.core.resource.ResourceRoot;

import java.util.List;
import java.util.Map;

/**
 * Validated, immutable runtime Skill descriptor.
 *
 * <p>Skill text is operational data supplied by deployment configuration. It
 * never grants permissions by itself.</p>
 */
public record RuntimeSkill(
        String name,
        String description,
        String version,
        List<Link> links,
        List<String> capabilities,
        String body,
        ResourceRoot root,
        String relativePath,
        Map<String, ResourceEntry> references) {

    public RuntimeSkill {
        if (name == null || name.isBlank()
                || description == null || description.isBlank()
                || body == null || root == null
                || relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Invalid runtime Skill");
        }
        version = version == null ? "" : version;
        links = links == null ? List.of() : List.copyOf(links);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        references = references == null ? Map.of() : Map.copyOf(references);
    }

    public record Link(String name, String description) {
        public Link {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Skill link name must not be blank");
            }
            description = description == null ? "" : description;
        }
    }
}
