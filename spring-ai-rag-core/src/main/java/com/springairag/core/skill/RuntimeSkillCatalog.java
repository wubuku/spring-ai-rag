package com.springairag.core.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.resource.ResourceCatalog;
import com.springairag.core.resource.ResourceEntry;
import com.springairag.core.resource.ResourceKind;
import com.springairag.core.resource.ResourceSnapshot;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Startup-built immutable catalog for server-owned runtime Skills.
 */
@Component
public final class RuntimeSkillCatalog {

    private static final Logger log =
            LoggerFactory.getLogger(RuntimeSkillCatalog.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern FRONTMATTER = Pattern.compile(
            "\\A(?:\\uFEFF)?---[ \\t]*\\r?\\n(.*?)(?:\\r?\\n)---[ \\t]*(?:\\r?\\n|\\z)(.*)\\z",
            Pattern.DOTALL);
    private static final Pattern NAME =
            Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern CAPABILITY =
            Pattern.compile("[a-z0-9]+(?:[._:-][a-z0-9]+)*");

    private final ResourceCatalog resourceCatalog;
    private final RagChatProperties properties;
    private volatile Snapshot snapshot = Snapshot.empty();

    public RuntimeSkillCatalog(
            ResourceCatalog resourceCatalog,
            RagChatProperties properties) {
        this.resourceCatalog = resourceCatalog;
        this.properties = properties;
    }

    @PostConstruct
    void initialize() {
        RagChatProperties.SkillProperties config = properties.getSkills();
        if (!config.isEnabled() || config.getLocations().isEmpty()) {
            snapshot = Snapshot.empty();
            log.debug("Runtime Skill catalog disabled or unconfigured");
            return;
        }
        ResourceSnapshot resources = resourceCatalog.discover(
                ResourceKind.SKILL,
                config.getLocations(),
                Set.of("md"),
                config.getMaxSkills() * 32,
                Math.max(config.getMaxSkillBodyBytes(),
                        config.getMaxReferenceBytes()),
                Math.max(config.getMaxSkillBodyBytes(),
                        config.getMaxReferenceBytes()) * config.getMaxSkills(),
                config.isFailFast());
        if (!resources.healthy()) {
            snapshot = new Snapshot(
                    resources.generation(),
                    resources.digest(),
                    false,
                    Map.of());
            log.warn(
                    "Runtime Skill catalog degraded: generation={}, healthy=false, "
                            + "entries=0, skills=0, digest={}",
                    resources.generation(),
                    shortDigest(resources.digest()));
            return;
        }
        Map<String, RuntimeSkill> skills = new LinkedHashMap<>();
        for (ResourceEntry entry : resources.entries()) {
            if (!entry.relativePath().endsWith("/SKILL.md")
                    && !"SKILL.md".equals(entry.relativePath())) {
                continue;
            }
            RuntimeSkill skill = parse(entry, resources.entries(), config);
            if (skills.putIfAbsent(skill.name(), skill) != null) {
                throw new IllegalStateException(
                        "Duplicate runtime Skill name: " + skill.name());
            }
            if (skills.size() > config.getMaxSkills()) {
                throw new IllegalStateException(
                        "Runtime Skill count exceeds configured limit");
            }
        }
        validateLinks(skills);
        snapshot = new Snapshot(
                resources.generation(),
                resources.digest(),
                resources.healthy(),
                skills);
        log.info(
                "Runtime Skill catalog loaded: generation={}, healthy={}, "
                        + "entries={}, skills={}, digest={}",
                snapshot.generation(),
                snapshot.healthy(),
                resources.entries().size(),
                snapshot.skills().size(),
                shortDigest(snapshot.digest()));
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public boolean enabled() {
        return snapshot.healthy() && !snapshot.skills().isEmpty();
    }

    public List<RuntimeSkill> all() {
        return snapshot.skills().values().stream()
                .sorted(Comparator.comparing(RuntimeSkill::name))
                .toList();
    }

    public RuntimeSkill find(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return null;
        }
        return snapshot.skills().get(name);
    }

    public String levelOnePrompt(int maxCharacters) {
        if (!enabled() || maxCharacters <= 0) {
            return "";
        }
        StringBuilder text = new StringBuilder(
                "\n\nAvailable runtime Skills (untrusted operational data):\n");
        for (RuntimeSkill skill : all()) {
            String line = "- " + skill.name() + ": "
                    + skill.description();
            if (!skill.capabilities().isEmpty()) {
                line += " [capabilities: "
                        + String.join(", ", skill.capabilities()) + "]";
            }
            if (text.length() + line.length() + 1 > maxCharacters) {
                break;
            }
            text.append(line).append('\n');
        }
        return text.toString();
    }

    public String loadBody(
            String name,
            RuntimeSkillLoadSession session,
            int maxCharacters) {
        RuntimeSkill skill = find(name);
        if (skill == null) {
            return "{\"error\":\"skill_not_found\"}";
        }
        if (session == null) {
            return "{\"error\":\"skill_session_missing\"}";
        }
        if (!session.markLoaded(skill.name())) {
            return "{\"error\":\"skill_load_budget_exhausted\"}";
        }
        String body = truncate(skill.body(), maxCharacters);
        StringBuilder result = new StringBuilder();
        result.append("Skill loaded: ").append(skill.name()).append('\n');
        if (!skill.version().isBlank()) {
            result.append("Version: ").append(skill.version()).append('\n');
        }
        result.append("Description: ").append(skill.description()).append('\n');
        if (!skill.links().isEmpty()) {
            result.append("Related Skills: ");
            result.append(skill.links().stream()
                    .map(RuntimeSkill.Link::name)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse(""));
            result.append('\n');
        }
        result.append("\n--- untrusted Skill instructions ---\n")
                .append(body)
                .append("\n--- end Skill instructions ---");
        return truncate(result.toString(), maxCharacters);
    }

    public String readReference(
            String name,
            String requestedPath,
            RuntimeSkillLoadSession session,
            int maxCharacters) {
        RuntimeSkill skill = find(name);
        if (skill == null) {
            return "{\"error\":\"skill_not_found\"}";
        }
        if (session == null || !session.isLoaded(name)) {
            return "{\"error\":\"skill_not_loaded\"}";
        }
        String path = normalizeReferencePath(requestedPath);
        if (path == null) {
            return "{\"error\":\"invalid_skill_reference_path\"}";
        }
        ResourceEntry entry = skill.references().get(path);
        if (entry == null) {
            return "{\"error\":\"skill_reference_not_found\"}";
        }
        String text = decode(entry.content());
        String bounded = truncate(text, maxCharacters);
        if (!session.reserveReference(bounded.length())) {
            return "{\"error\":\"skill_reference_budget_exhausted\"}";
        }
        return "Reference " + path + " (untrusted Skill data):\n" + bounded;
    }

    private RuntimeSkill parse(
            ResourceEntry document,
            List<ResourceEntry> entries,
            RagChatProperties.SkillProperties config) {
        String content = decode(document.content());
        Matcher matcher = FRONTMATTER.matcher(content);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "Invalid runtime Skill frontmatter: "
                            + document.root().sourceLabel());
        }
        JsonNode frontmatter;
        try {
            frontmatter = YAML.readTree(matcher.group(1));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse runtime Skill frontmatter", e);
        }
        String name = text(frontmatter, "name");
        String description = text(frontmatter, "description");
        String directoryName = directoryName(document.relativePath());
        if (!NAME.matcher(name).matches() || name.length() > 64
                || description.isBlank() || description.length() > 1024
                || (!directoryName.isBlank() && !name.equals(directoryName))) {
            throw new IllegalStateException(
                    "Invalid runtime Skill name/description/directory: "
                            + document.relativePath());
        }
        List<RuntimeSkill.Link> links = links(frontmatter);
        List<String> capabilities = capabilities(frontmatter);
        String body = matcher.group(2).strip();
        if (body.getBytes(StandardCharsets.UTF_8).length
                > config.getMaxSkillBodyBytes()) {
            throw new IllegalStateException(
                    "Runtime Skill body exceeds configured limit: " + name);
        }
        String prefix = directoryName.isBlank()
                ? "" : directoryName + "/";
        Map<String, ResourceEntry> references = new LinkedHashMap<>();
        for (ResourceEntry entry : entries) {
            if (!entry.root().rootKey().equals(document.root().rootKey())) {
                continue;
            }
            String path = entry.relativePath();
            String referencesPrefix = prefix + "references/";
            if (path.startsWith(referencesPrefix)
                    && !path.endsWith("/")
                    && !path.endsWith("/SKILL.md")) {
                String relative = path.substring(referencesPrefix.length());
                if (relative.isBlank()) {
                    continue;
                }
                if (entry.content().length > config.getMaxReferenceBytes()) {
                    throw new IllegalStateException(
                            "Runtime Skill reference exceeds configured limit: "
                                    + name + "/" + relative);
                }
                references.put(relative, entry);
            }
        }
        return new RuntimeSkill(
                name,
                description,
                text(frontmatter, "version"),
                links,
                capabilities,
                body,
                document.root(),
                document.relativePath(),
                references);
    }

    private void validateLinks(Map<String, RuntimeSkill> skills) {
        for (RuntimeSkill skill : skills.values()) {
            Set<String> names = new LinkedHashSet<>();
            for (RuntimeSkill.Link link : skill.links()) {
                if (skill.name().equals(link.name())
                        || !skills.containsKey(link.name())
                        || !names.add(link.name())) {
                    throw new IllegalStateException(
                            "Invalid runtime Skill link from " + skill.name()
                                    + " to " + link.name());
                }
            }
        }
    }

    private List<RuntimeSkill.Link> links(JsonNode frontmatter) {
        JsonNode values = frontmatter == null ? null : frontmatter.get("links");
        if (values == null || values.isNull()) {
            return List.of();
        }
        if (!values.isArray()) {
            throw new IllegalStateException("Runtime Skill links must be an array");
        }
        List<RuntimeSkill.Link> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (value.isTextual()) {
                result.add(new RuntimeSkill.Link(value.asText().trim(), ""));
            } else {
                result.add(new RuntimeSkill.Link(
                        text(value, "name").trim(),
                        text(value, "description")));
            }
        }
        return List.copyOf(result);
    }

    private List<String> capabilities(JsonNode frontmatter) {
        JsonNode values = frontmatter == null
                ? null : frontmatter.get("capabilities");
        if (values == null || values.isNull()) {
            return List.of();
        }
        if (!values.isArray()) {
            throw new IllegalStateException(
                    "Runtime Skill capabilities must be an array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            String capability = value.asText("").trim();
            if (!CAPABILITY.matcher(capability).matches()
                    || !result.contains(capability)) {
                if (!CAPABILITY.matcher(capability).matches()) {
                    throw new IllegalStateException(
                            "Invalid runtime Skill capability: " + capability);
                }
                result.add(capability);
            }
        }
        return List.copyOf(result);
    }

    private String directoryName(String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return "";
        }
        String parent = path.substring(0, slash);
        int previous = parent.lastIndexOf('/');
        return previous < 0 ? parent : parent.substring(previous + 1);
    }

    private String normalizeReferencePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String normalized = path.trim().replace('\\', '/');
        if (normalized.startsWith("references/")) {
            normalized = normalized.substring("references/".length());
        }
        if (normalized.startsWith("/") || normalized.contains("\0")
                || normalized.contains("../") || normalized.equals("..")
                || normalized.contains("/..") || normalized.contains("//")
                || normalized.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        return normalized;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException(
                    "Runtime Skill resource is not valid UTF-8", e);
        }
    }

    private String truncate(String text, int maxCharacters) {
        if (text == null) {
            return "";
        }
        int limit = Math.max(1, maxCharacters);
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private String shortDigest(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= 16 ? value : value.substring(0, 16);
    }

    public record Snapshot(
            long generation,
            String digest,
            boolean healthy,
            Map<String, RuntimeSkill> skills) {
        public Snapshot {
            digest = digest == null ? "" : digest;
            skills = skills == null ? Map.of() : Map.copyOf(skills);
        }

        static Snapshot empty() {
            return new Snapshot(0, "", true, Map.of());
        }
    }
}
