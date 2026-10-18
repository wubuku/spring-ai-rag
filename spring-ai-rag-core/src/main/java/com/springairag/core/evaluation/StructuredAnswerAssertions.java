package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StructuredAnswerAssertions {
    private static final Set<String> FIELDS = Set.of("id", "type", "forbidden", "value", "unit", "min", "max", "alternatives");
    private static final Pattern NUMBER = Pattern.compile("(?<![a-zA-Z0-9_.,+\\-负零〇一二两三四五六七八九十百千万亿])((?:[+\\-]|负)?(?:(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?|[零〇一二两三四五六七八九十百千万亿]+))");

    private StructuredAnswerAssertions() { }

    public record Rule(String id, String type, boolean forbidden, String value, String unit,
                       String min, String max, List<String> alternatives) { }

    public static List<Rule> parse(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray() || node.size() > 32) throw new IllegalArgumentException("assertions requires at most 32 rules");
        List<Rule> rules = new ArrayList<>();
        Set<String> identifiers = new HashSet<>();
        for (JsonNode entry : node) {
            if (!entry.isObject()) throw new IllegalArgumentException("assertion must be an object");
            entry.fieldNames().forEachRemaining(field -> {
                if (!FIELDS.contains(field)) throw new IllegalArgumentException("unknown assertion field: " + field);
            });
            String id = text(entry, "id", 64);
            if (!identifiers.add(id)) throw new IllegalArgumentException("duplicate assertion id");
            String type = text(entry, "type", 32);
            if (!Set.of("TOKEN", "TEXT_ANY", "QUANTITY", "NUMBER_RANGE").contains(type)) throw new IllegalArgumentException("unknown assertion type");
            if (entry.has("forbidden") && !entry.get("forbidden").isBoolean()) throw new IllegalArgumentException("forbidden must be boolean");
            boolean forbidden = entry.path("forbidden").asBoolean(false);
            Set<String> allowed = new HashSet<>(Set.of("id", "type", "forbidden"));
            String value = null;
            String unit = null;
            String min = null;
            String max = null;
            List<String> alternatives = new ArrayList<>();
            if ("TEXT_ANY".equals(type)) {
                allowed.add("alternatives");
                JsonNode values = entry.get("alternatives");
                if (values == null || !values.isArray() || values.isEmpty() || values.size() > 8) throw new IllegalArgumentException("alternatives requires 1..8 strings");
                for (JsonNode item : values) {
                    if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 128) throw new IllegalArgumentException("invalid alternative");
                    alternatives.add(item.asText());
                }
            } else if ("TOKEN".equals(type)) {
                allowed.add("value");
                value = text(entry, "value", 128);
            } else {
                allowed.add("unit");
                unit = text(entry, "unit", 32);
                if ("QUANTITY".equals(type)) {
                    allowed.add("value");
                    value = text(entry, "value", 32);
                    decimal(value);
                } else {
                    allowed.addAll(Set.of("min", "max"));
                    min = text(entry, "min", 32);
                    max = text(entry, "max", 32);
                    if (decimal(min).compareTo(decimal(max)) > 0) throw new IllegalArgumentException("min exceeds max");
                }
            }
            entry.fieldNames().forEachRemaining(field -> {
                if (!allowed.contains(field)) throw new IllegalArgumentException("field not allowed for assertion type: " + field);
            });
            rules.add(new Rule(id, type, forbidden, value, unit, min, max, List.copyOf(alternatives)));
        }
        return List.copyOf(rules);
    }

    public static List<Map<String, Object>> evaluate(List<Rule> rules, String answer) {
        String normalized = normalize(answer == null ? "" : answer);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Rule rule : rules) {
            int[] span = find(rule, normalized);
            boolean matched = span != null;
            results.add(Map.of("id", rule.id(), "type", rule.type(), "matched", matched,
                    "passed", rule.forbidden() != matched,
                    "start", matched ? normalized.codePointCount(0, span[0]) : -1,
                    "end", matched ? normalized.codePointCount(0, span[1]) : -1,
                    "offsetUnit", "NORMALIZED_UNICODE_CODE_POINT",
                    "matchedText", matched ? normalized.substring(span[0], normalized.offsetByCodePoints(span[0], Math.min(128, normalized.codePointCount(span[0], span[1])))) : ""));
        }
        return List.copyOf(results);
    }

    private static int[] find(Rule rule, String answer) {
        if ("TOKEN".equals(rule.type())) {
            Matcher matcher = Pattern.compile("(?<![a-z0-9_\\-])" + Pattern.quote(normalize(rule.value())) + "(?![a-z0-9_\\-])").matcher(answer);
            return matcher.find() ? new int[]{matcher.start(), matcher.end()} : null;
        }
        if ("TEXT_ANY".equals(rule.type())) {
            for (String value : rule.alternatives()) {
                String candidate = normalize(value);
                int start = answer.indexOf(candidate);
                if (start >= 0) return new int[]{start, start + candidate.length()};
            }
            return null;
        }
        Matcher numbers = NUMBER.matcher(answer.replace('−', '-'));
        while (numbers.find()) {
            int end = numbers.end();
            while (end < answer.length() && (Character.isWhitespace(answer.charAt(end)) || Character.isSpaceChar(answer.charAt(end)) || answer.charAt(end) == '\u0085')) end++;
            String unit = normalize(rule.unit());
            if (!answer.startsWith(unit, end)) continue;
            int unitEnd = end + unit.length();
            if (unitEnd < answer.length() && unit.substring(unit.length() - 1).matches("[a-z0-9]") && answer.substring(unitEnd, unitEnd + 1).matches("[a-z0-9_/^]")) continue;
            try {
                BigDecimal value = numeric(numbers.group(1));
                boolean matched = "QUANTITY".equals(rule.type())
                        ? value.compareTo(decimal(rule.value())) == 0
                        : value.compareTo(decimal(rule.min())) >= 0 && value.compareTo(decimal(rule.max())) <= 0;
                if (matched) return new int[]{numbers.start(), unitEnd};
            } catch (IllegalArgumentException ignored) {
                continue;
            }
        }
        return null;
    }

    static BigDecimal numeric(String text) {
        if (text.length() > 32) throw new IllegalArgumentException("number too long");
        if (text.startsWith("负")) return numeric(text.substring(1)).negate();
        if ((text.startsWith("-") || text.startsWith("+")) && text.substring(1).matches("[零〇一二两三四五六七八九十百千万亿]+")) return numeric(text.substring(1)).multiply(BigDecimal.valueOf(text.startsWith("-") ? -1 : 1));
        text = text.replace('〇', '零').replace('两', '二');
        if (!text.matches("[零〇一二两三四五六七八九十百千万亿]+")) return decimal(text.replace(",", ""));
        if (text.matches("[零〇一二两三四五六七八九]+")) {
            StringBuilder digits = new StringBuilder();
            for (char character : text.toCharArray()) digits.append(character == '两' ? 2 : character == '〇' ? 0 : "零一二三四五六七八九".indexOf(character));
            return decimal(digits.toString());
        }
        for (String magnitude : List.of("亿", "万")) {
            int split = text.indexOf(magnitude);
            if (split >= 0) {
                if (split == 0 || split != text.lastIndexOf(magnitude)) throw new IllegalArgumentException("invalid Chinese integer");
                String tail = text.substring(split + 1);
                if (tail.startsWith("零")) tail = tail.substring(1);
                return numeric(text.substring(0, split)).multiply(BigDecimal.valueOf("亿".equals(magnitude) ? 100000000 : 10000))
                        .add(tail.isEmpty() ? BigDecimal.ZERO : numeric(tail));
            }
        }
        if (text.startsWith("零") || text.startsWith("〇") || !text.matches("(?:[一二两三四五六七八九]千)?零?(?:[一二两三四五六七八九]百)?零?(?:[一二两三四五六七八九]?十)?[零一二两三四五六七八九]?") || text.contains("零零")) throw new IllegalArgumentException("invalid Chinese integer");
        long total = 0;
        long section = 0;
        long digit = 0;
        for (char character : text.toCharArray()) {
            int value = "零一二三四五六七八九".indexOf(character);
            if (character == '〇') value = 0;
            if (character == '两') value = 2;
            if (value >= 0) { digit = value; continue; }
            long multiplier = switch (character) { case '十' -> 10; case '百' -> 100; case '千' -> 1000; case '万' -> 10000; default -> 100000000; };
            if (multiplier < 10000) section += (digit == 0 ? 1 : digit) * multiplier;
            else { total += (section + digit) * multiplier; section = 0; }
            digit = 0;
        }
        return BigDecimal.valueOf(total + section + digit);
    }

    private static BigDecimal decimal(String value) {
        if (!value.matches("[+\\-]?[0-9]{1,18}(?:\\.[0-9]{1,9})?")) throw new IllegalArgumentException("invalid finite decimal");
        return new BigDecimal(value);
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() > max) throw new IllegalArgumentException("invalid assertion " + field);
        return value.asText();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
