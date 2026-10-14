package com.springairag.core.rag;

import com.springairag.core.config.RagProperties;
import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.api.dto.RetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖引用查询增强器：空资料按 allowEmptyContext 回退、编号参考资料
 * 拼装与用户问题占位。
 */
class CitationQueryAugmenterTest {

    private RagProperties properties;
    private CitationQueryAugmenter augmenter;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        augmenter = new CitationQueryAugmenter(properties);
    }

    private Document document(String text) {
        return new Document(text);
    }

    @Test
    void returnsOriginalQueryWhenEmptyContextIsAllowed() {
        properties.getChat().getKnowledge().setAllowEmptyContext(true);
        Query query = Query.builder().text("question").build();

        Query result = augmenter.augment(query, List.of());

        assertSame(query, result);
    }

    @Test
    void injectsGuardrailInstructionWhenEmptyContextIsDisallowed() {
        Query query = Query.builder().text("question").build();

        Query result = augmenter.augment(query, List.of());

        assertTrue(result.text().contains("未检索到可用资料"));
        assertTrue(result.text().contains("不要编造答案"));
    }

    @Test
    void augmentsQueryWithNumberedReferencesAndUserQuestion() {
        Query query = Query.builder().text("what is rag?").build();
        List<Document> documents = List.of(
                document("first source"),
                document("second source"));

        Query result = augmenter.augment(query, documents);

        assertTrue(result.text().contains("[S1] first source"));
        assertTrue(result.text().contains("[S2] second source"));
        assertTrue(result.text().contains("用户问题：\nwhat is rag?"));
        assertTrue(result.text().contains("请仅依据以下参考资料回答用户问题"));
    }

    @Test
    void usesRegistryHandleWhenAuthorizedContextIsPresent() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector(3, 3, 3);
        RetrievalResult retrieval = new RetrievalResult();
        retrieval.setDocumentId("doc-1");
        retrieval.setChunkIndex(2);
        retrieval.setChunkText("first source");
        trace.record(List.of(retrieval));
        Query query = Query.builder()
                .text("question")
                .context(java.util.Map.of(
                        com.springairag.core.rag.ProjectDocumentRetriever.CONTEXT_KEY,
                        new AuthorizedRetrievalContext(
                                RetrievalScope.unscoped(),
                                new RetrievalOptions(3, 0.2, true, false, 0.5, 0.5),
                                trace,
                                "session",
                                ChatPrincipal.local())))
                .build();
        Document document = Document.builder()
                .text("first source")
                .metadata(java.util.Map.of("documentId", "doc-1", "chunkIndex", 2))
                .build();

        Query result = augmenter.augment(query, List.of(document));

        assertTrue(result.text().contains("[c1] first source"));
        assertTrue(!result.text().contains("[S1] first source"));
    }
}
