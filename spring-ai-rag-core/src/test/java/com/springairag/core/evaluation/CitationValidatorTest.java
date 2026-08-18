package com.springairag.core.evaluation;

import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.CitationValidation;
import com.springairag.api.enums.ChatMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CitationValidatorTest {

    private final CitationValidator validator = new CitationValidator();

    @Test
    void classifiesPlainMissingInvalidPartialAndValid() {
        assertEquals(CitationValidation.NOT_APPLICABLE,
                validator.validate(ChatMode.PLAIN, "回答 [S1]", sources("S1")).status());
        assertEquals(CitationValidation.NOT_APPLICABLE,
                validator.validate(ChatMode.KNOWLEDGE, "没有引用", List.of()).status());
        assertEquals(CitationValidation.INVALID_CITATION,
                validator.validate(ChatMode.KNOWLEDGE, "错误 [S9]", List.of()).status());
        assertEquals(CitationValidation.MISSING_CITATION,
                validator.validate(ChatMode.AGENT, "没有引用", sources("S1", "S2")).status());
        assertEquals(CitationValidation.INVALID_CITATION,
                validator.validate(ChatMode.KNOWLEDGE, "只用了 [S9]", sources("S1")).status());
        assertEquals(CitationValidation.PARTIAL,
                validator.validate(ChatMode.KNOWLEDGE, "同时 [S1] 和 [S9]", sources("S1", "S2")).status());
        CitationValidation valid = validator.validate(
                ChatMode.KNOWLEDGE, "依据 [S1][S2]", sources("S1", "S2"));
        assertEquals(CitationValidation.VALID, valid.status());
        assertEquals(2, valid.citedSourceCount());
    }

    private List<ChatSource> sources(String... ids) {
        List<ChatSource> list = new java.util.ArrayList<>();
        for (String id : ids) {
            ChatSource source = new ChatSource();
            source.setCitationId(id);
            list.add(source);
        }
        return list;
    }
}
