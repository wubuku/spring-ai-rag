package com.springairag.core.evaluation;

import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.CitationValidation;
import com.springairag.api.enums.ChatMode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只解析约定的 [S1] 形式，不做自然语言 claim coverage。
 */
@Component
public class CitationValidator {

    private static final Pattern TOKEN = Pattern.compile("\\[S(\\d+)\\]");

    public CitationValidation validate(
            ChatMode mode,
            String answer,
            List<ChatSource> sources) {
        List<String> available = new ArrayList<>();
        if (sources != null) {
            for (ChatSource source : sources) {
                if (source != null && source.getCitationId() != null
                        && !source.getCitationId().isBlank()) {
                    available.add(source.getCitationId());
                }
            }
        }
        Set<String> availableSet = new LinkedHashSet<>(available);
        List<String> cited = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        if (answer != null) {
            Matcher matcher = TOKEN.matcher(answer);
            Set<String> seen = new LinkedHashSet<>();
            while (matcher.find()) {
                String id = "S" + matcher.group(1);
                if (!seen.add(id)) {
                    continue;
                }
                if (availableSet.contains(id)) {
                    cited.add(id);
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
}
