package com.springairag.api.validation;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Iterator;
import java.util.Map;

/**
 * {@link NoNulInJsonTree} 的实现。
 */
public final class NoNulInJsonTreeValidator
        implements ConstraintValidator<NoNulInJsonTree, Object> {

    private static final int MAX_DEPTH = 256;

    public static boolean isValidTree(Object value) {
        return walk(value, 0);
    }

    private static boolean walk(Object value, int depth) {
        if (value == null) {
            return true;
        }
        if (depth > MAX_DEPTH) {
            return false;
        }
        if (value instanceof CharSequence text) {
            return NoNulCharacterValidator.isValid(text);
        }
        if (value instanceof JsonNode node) {
            return walkJsonNode(node, depth);
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!walk(entry.getKey(), depth + 1)
                        || !walk(entry.getValue(), depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (!walk(item, depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        if (value instanceof Object[] array) {
            for (Object item : array) {
                if (!walk(item, depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        return true;
    }

    private static boolean walkJsonNode(JsonNode node, int depth) {
        if (node.isTextual()) {
            return NoNulCharacterValidator.isValid(node.asText());
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!NoNulCharacterValidator.isValid(field.getKey())
                        || !walkJsonNode(field.getValue(), depth + 1)) {
                    return false;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (!walkJsonNode(item, depth + 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        return isValidTree(value);
    }
}
