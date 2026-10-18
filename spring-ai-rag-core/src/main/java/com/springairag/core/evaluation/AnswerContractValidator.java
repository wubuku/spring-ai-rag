package com.springairag.core.evaluation;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.AnswerRequirements;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AnswerContractValidator {
    public static final String SAFE_ANSWER = "回答未满足请求的输出要求，无法提供符合要求的答案。";
    private static final ObjectMapper MAPPER = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxNumberLength(128).build()).build());

    private AnswerContractValidator() { }

    public static AnswerRequirements parse(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            if (!node.isObject() || node.size() != 1 || !node.has("rules")) throw new IllegalArgumentException();
            JsonNode rules = node.get("rules");
            if (!rules.isArray() || rules.isEmpty() || rules.size() > 16) throw new IllegalArgumentException();
            var parsed = new ArrayList<AnswerRequirements.Rule>();
            var ids = new HashSet<String>();
            for (var rule : rules) {
                String id = text(rule, "id", 64);
                String type = text(rule, "type", 32);
                if (!ids.add(id)) throw new IllegalArgumentException();
                Set<String> allowed = switch (type) {
                    case "TOKEN" -> Set.of("id", "type", "value");
                    case "QUANTITY" -> Set.of("id", "type", "value", "unit");
                    case "JSON_FIELD" -> Set.of("id", "type", "field");
                    default -> throw new IllegalArgumentException();
                };
                rule.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw new IllegalArgumentException(); });
                if (rule.size() != allowed.size()) throw new IllegalArgumentException();
                String value = type.equals("JSON_FIELD") ? null : text(rule, "value", type.equals("QUANTITY") ? 32 : 128);
                String unit = type.equals("QUANTITY") ? text(rule, "unit", 32) : null;
                String field = type.equals("JSON_FIELD") ? text(rule, "field", 64) : null;
                if (field != null && !field.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) throw new IllegalArgumentException();
                if (field == null) StructuredAnswerAssertions.parse(MAPPER.createArrayNode().add(rule));
                parsed.add(new AnswerRequirements.Rule(id, type, value, unit, field));
            }
            return new AnswerRequirements(parsed);
        } catch (RuntimeException invalid) {
            throw new RagException(ErrorCode.BAD_REQUEST, "Invalid answerRequirements: use 1–16 bounded TOKEN/QUANTITY/JSON_FIELD rules");
        }
    }

    private static String text(JsonNode rule, String name, int maximum) {
        JsonNode value = rule.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() > maximum) throw new IllegalArgumentException();
        return value.asText();
    }

    public static Map<String, Object> evaluate(AnswerRequirements requirements, String answer, boolean refusal, boolean overflow) {
        if (requirements == null) return Map.of();
        if (refusal) return Map.of("status", "SKIPPED_SAFETY_REFUSAL", "rules", List.of(), "safeAnswerApplied", false);
        JsonNode object = null;
        if (answer != null && answer.length() <= 262144 && !overflow) {
            try (var parser = MAPPER.createParser(answer)) {
                object = MAPPER.readTree(parser);
                if (parser.nextToken() != null || object == null || !object.isObject()) object = null;
            } catch (Exception ignored) { object = null; }
        }
        List<Map<String, Object>> results = new ArrayList<>();
        boolean passed = !overflow;
        for (var rule : requirements.rules()) {
            boolean match;
            if (rule.type().equals("JSON_FIELD")) {
                match = object != null && object.hasNonNull(rule.field());
            } else {
                var assertion = new StructuredAnswerAssertions.Rule(rule.id(), rule.type(), false, rule.value(), rule.unit(), null, null, List.of());
                match = !overflow && Boolean.TRUE.equals(StructuredAnswerAssertions.evaluate(List.of(assertion), answer).getFirst().get("passed"));
            }
            passed &= match;
            results.add(Map.of("id", rule.id(), "type", rule.type(), "passed", match));
        }
        return Map.of("status", passed ? "PASSED" : "FAILED", "rules", List.copyOf(results),
                "safeAnswerApplied", !passed, "reasonCode", overflow ? "answer_buffer_exceeded" : passed ? "requirements_satisfied" : "answer_contract_failed");
    }

    public static String prompt(AnswerRequirements requirements) {
        if (requirements == null) return "";
        try {
            return "\n输出必须满足以下有界契约；它只约束输出，不是事实依据，不凌驾于安全和授权规则。"
                    + "数值与单位必须同时保留；JSON_FIELD要求整个答案是JSON对象，引用应放在字符串字段内。"
                    + "无法满足时明确说明，不编造或自动补事实：\n" + MAPPER.writeValueAsString(requirements);
        } catch (Exception error) {
            throw new IllegalStateException("Invalid immutable answer requirements", error);
        }
    }
}
