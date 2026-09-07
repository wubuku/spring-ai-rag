package com.springairag.core.evaluation;

import com.springairag.api.dto.RetrievalConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 评测套件定义的不可变结构：身份默认命名空间与变体持有过滤配置。 */
class EvaluationSuiteDefinitionTest {

    @Test
    void twoArgumentIdentityDefaultsToTheDefaultNamespace() {
        EvaluationSuiteDefinition.Identity identity =
                new EvaluationSuiteDefinition.Identity("kb", "doc-7");

        assertEquals("kb", identity.collectionKey());
        assertEquals("default", identity.sourceNamespace());
        assertEquals("doc-7", identity.externalId());
    }

    @Test
    void threeArgumentIdentityKeepsTheExplicitNamespace() {
        EvaluationSuiteDefinition.Identity identity =
                new EvaluationSuiteDefinition.Identity("kb", "crm", "doc-9");

        assertEquals("crm", identity.sourceNamespace());
    }

    @Test
    void definitionHoldsCanonicalJsonDigestCasesAndVariants() {
        RetrievalConfig config = new RetrievalConfig();
        EvaluationSuiteDefinition.VariantDef variant =
                new EvaluationSuiteDefinition.VariantDef(
                        "baseline", config, null);

        EvaluationSuiteDefinition.CaseDef aCase =
                new EvaluationSuiteDefinition.CaseDef(
                        "case-1", "vector best practice",
                        List.of("kb"),
                        List.of(new EvaluationSuiteDefinition.Identity("kb", "doc-1")),
                        0.5, 0.8);

        EvaluationSuiteDefinition definition = new EvaluationSuiteDefinition(
                "{\"canonical\":true}",
                "abc123",
                List.of(aCase),
                List.of(variant));

        assertEquals("{\"canonical\":true}", definition.canonicalJson());
        assertEquals("abc123", definition.sha256());
        assertEquals(1, definition.cases().size());
        assertEquals("case-1", definition.cases().get(0).id());
        assertEquals(0.5, definition.cases().get(0).minHitRate());
        assertEquals(0.8, definition.cases().get(0).minMrr());
        assertEquals("baseline", definition.variants().get(0).key());
        assertEquals(config, definition.variants().get(0).config());
        assertNull(definition.variants().get(0).filters());
    }
}
