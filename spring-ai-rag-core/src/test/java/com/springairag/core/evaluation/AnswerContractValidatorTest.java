package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class AnswerContractValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"rules\":[]}", "{\"rules\":[{\"id\":\"a\",\"type\":\"REGEX\",\"value\":\".*\"}]}",
            "{\"rules\":[{\"id\":\"a\",\"type\":\"TOKEN\",\"value\":1}]}",
            "{\"rules\":[{\"id\":\"a\",\"type\":\"QUANTITY\",\"value\":\"NaN\",\"unit\":\"条\"}]}",
            "{\"rules\":[{\"id\":\"a\",\"type\":\"JSON_FIELD\",\"field\":\"a.b\"}]}",
            "{\"rules\":[{\"id\":\"a\",\"type\":\"TOKEN\",\"value\":\"x\",\"forbidden\":false}]}"})
    void malformedRulesAreRejectedBeforeExecution(String json) throws Exception {
        assertEquals("BAD_REQUEST", assertThrows(RagException.class, () -> AnswerContractValidator.parse(mapper.readTree(json))).getErrorCode());
    }

    @Test
    void quantityAndTokenUseDeterministicBoundariesWithoutInventingUnits() throws Exception {
        var rules = AnswerContractValidator.parse(mapper.readTree("""
                {"rules":[{"id":"count","type":"QUANTITY","value":"0","unit":"条"},
                {"id":"code","type":"TOKEN","value":"SAFE-42"}]}
                """));
        assertEquals("PASSED", AnswerContractValidator.evaluate(rules, "零条 SAFE-42", false, false).get("status"));
        for (String answer : java.util.List.of("10条 SAFE-42", "0 SAFE-42", "0天 SAFE-42", "0条 SAFE-420")) {
            assertEquals("FAILED", AnswerContractValidator.evaluate(rules, answer, false, false).get("status"));
        }
        assertFalse(AnswerContractValidator.evaluate(rules, "0条 SAFE-42", false, false).toString().contains("matchedText"));
        assertEquals("FAILED", AnswerContractValidator.evaluate(rules, "0条 SAFE-42", false, true).get("status"));
        assertEquals("SKIPPED_SAFETY_REFUSAL", AnswerContractValidator.evaluate(rules, "", true, true).get("status"));
    }

    @Test
    void jsonFieldsRequireWholeObjectAndNonNullValue() throws Exception {
        var rules = AnswerContractValidator.parse(mapper.readTree("{\"rules\":[{\"id\":\"field\",\"type\":\"JSON_FIELD\",\"field\":\"count\"}]}"));
        assertEquals("PASSED", AnswerContractValidator.evaluate(rules, "{\"count\":0}", false, false).get("status"));
        for (String answer : java.util.List.of("{}", "{\"count\":null}", "[]", "{\"count\":0} trailing", "{\"count\":1,\"count\":0}", "```json\n{\"count\":0}\n```")) {
            assertEquals("FAILED", AnswerContractValidator.evaluate(rules, answer, false, false).get("status"));
        }
    }
}
