package com.springairag.core.evaluation;

import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.CitationValidation;
import com.springairag.api.enums.ChatMode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析请求级 [c1] 和兼容 [S1] 形式，不做自然语言 claim coverage。
 */
@Component
public class CitationValidator {

    private static final Pattern TOKEN = Pattern.compile("\\[((?:S|c)(\\d+))\\]");

    public CitationValidation validate(
            ChatMode mode,
            String answer,
            List<ChatSource> sources) {
        // A set, not a list: dirty data can let two sources share one citation id.
        // Keeping the duplicate would inflate sourceCount, so the UI would report
        // "2/2 sources cited" for an answer that actually cites one source.
        Map<String, String> aliases = new HashMap<>();
        Set<String> available = new LinkedHashSet<>();
        if (sources != null) {
            for (ChatSource source : sources) {
                if (source == null) {
                    continue;
                }
                String canonical = usable(source.getCitationId())
                        ? source.getCitationId()
                        : source.getSourceHandle();
                if (!usable(canonical)) {
                    continue;
                }
                available.add(canonical);
                if (usable(source.getCitationId())) {
                    aliases.put(source.getCitationId(), canonical);
                }
                if (usable(source.getSourceHandle())) {
                    aliases.put(source.getSourceHandle(), canonical);
                }
            }
        }
        List<String> cited = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        if (answer != null) {
            Matcher matcher = TOKEN.matcher(answer);
            Set<String> seen = new LinkedHashSet<>();
            while (matcher.find()) {
                String id = matcher.group(1);
                String canonical = aliases.get(id);
                String reported = canonical != null ? canonical : id;
                if (!seen.add(reported)) {
                    continue;
                }
                if (canonical != null) {
                    cited.add(canonical);
                } else {
                    invalid.add(id);
                }
            }
        }

        if (mode == ChatMode.PLAIN) {
            return new CitationValidation(
                    CitationValidation.NOT_APPLICABLE,
                    List.of(), List.of(), List.of(), 0, 0);
        }
        if (available.isEmpty() && cited.isEmpty() && invalid.isEmpty()) {
            return new CitationValidation(
                    CitationValidation.NOT_APPLICABLE,
                    List.of(), List.of(), List.of(), 0, 0);
        }
        if (available.isEmpty() && !invalid.isEmpty()) {
            return new CitationValidation(
                    CitationValidation.INVALID_CITATION,
                    List.of(), List.of(), List.copyOf(invalid), 0, 0);
        }
        if (!available.isEmpty() && cited.isEmpty() && invalid.isEmpty()) {
            return new CitationValidation(
                    CitationValidation.MISSING_CITATION,
                    List.copyOf(available), List.of(), List.of(), 0, available.size());
        }
        if (!invalid.isEmpty() && cited.isEmpty()) {
            return new CitationValidation(
                    CitationValidation.INVALID_CITATION,
                    List.copyOf(available), List.of(), List.copyOf(invalid),
                    0, available.size());
        }
        if (!invalid.isEmpty()) {
            return new CitationValidation(
                    CitationValidation.PARTIAL,
                    List.copyOf(available), List.copyOf(cited), List.copyOf(invalid),
                    cited.size(), available.size());
        }
        return new CitationValidation(
                CitationValidation.VALID,
                List.copyOf(available), List.copyOf(cited), List.of(),
                cited.size(), available.size());
    }

    private boolean usable(String value) {
        return value != null && !value.isBlank();
    }
}
