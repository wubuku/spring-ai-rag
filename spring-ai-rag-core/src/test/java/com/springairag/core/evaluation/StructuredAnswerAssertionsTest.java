package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StructuredAnswerAssertionsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sharedBoundaryCorpus() throws Exception {
        Path path = Path.of("../testdata/answer-assertions/v1/cases.json");
        if (!Files.exists(path)) path = Path.of("testdata/answer-assertions/v1/cases.json");
        for (JsonNode entry : mapper.readTree(Files.readString(path))) {
            var rules = StructuredAnswerAssertions.parse(mapper.createArrayNode().add(entry.get("rule")));
            var result = StructuredAnswerAssertions.evaluate(rules, entry.path("answer").asText()).getFirst();
            assertEquals(entry.path("matched").asBoolean(), result.get("matched"), entry.toString());
            assertEquals(entry.path("rule").path("forbidden").asBoolean(false) != entry.path("matched").asBoolean(), result.get("passed"));
            if (entry.has("start")) assertEquals(entry.path("start").asInt(), result.get("start"));
        }
    }

    @Test
    void rejectsUnknownTypesFieldsRegexNonfiniteAndWrongTypes() throws Exception {
        for (String rule : java.util.List.of(
                "{\"id\":\"a\",\"type\":\"REGEX\",\"value\":\".*\"}",
                "{\"id\":\"a\",\"type\":\"TOKEN\",\"value\":\"x\",\"regex\":true}",
                "{\"id\":\"a\",\"type\":\"TOKEN\",\"value\":1}",
                "{\"id\":\"a\",\"type\":\"TOKEN\",\"value\":\"x\",\"forbidden\":\"false\"}",
                "{\"id\":\"a\",\"type\":\"QUANTITY\",\"value\":\"NaN\",\"unit\":\"条\"}",
                "{\"id\":\"a\",\"type\":\"NUMBER_RANGE\",\"min\":\"2\",\"max\":\"1\",\"unit\":\"条\"}",
                "{\"id\":\"a\",\"type\":\"TEXT_ANY\",\"alternatives\":[]}")) {
            JsonNode parsed = mapper.readTree("[" + rule + "]");
            assertThrows(IllegalArgumentException.class, () -> StructuredAnswerAssertions.parse(parsed), rule);
        }
    }
}
