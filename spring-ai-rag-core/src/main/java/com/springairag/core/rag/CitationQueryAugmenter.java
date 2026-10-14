package com.springairag.core.rag;

import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.config.RagChatProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adds bounded, numbered evidence to the original user query.
 */
@Component
public class CitationQueryAugmenter implements QueryAugmenter {

    private final RagChatProperties properties;

    public CitationQueryAugmenter(com.springairag.core.config.RagProperties properties) {
        this.properties = properties.getChat();
    }

    @Override
    public Query augment(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            if (properties.getKnowledge().isAllowEmptyContext()) {
                return query;
            }
            return new Query(
                    "当前授权范围内未检索到可用资料。请明确告知用户没有找到相关资料，不要编造答案。",
                    query.history(),
                    query.context());
        }
        RetrievalTraceCollector trace = trace(query);
        List<String> references = new ArrayList<>();
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            String citation = "S" + (index + 1);
            if (trace != null) {
                String sourceKey = sourceKey(document);
                String handle = trace.sourceHandle(sourceKey);
                if (handle == null) {
                    continue;
                }
                citation = handle;
            }
            references.add("[" + citation + "] " + document.getText());
        }
        String text = """
                请仅依据以下参考资料回答用户问题。无法从资料确认的内容要明确说明。
                引用时优先使用资料中的 [cN]；旧资料也可能使用 [S1] 等 [SN]。

                参考资料：
                %s

                用户问题：
                %s
                """.formatted(String.join("\n\n", references), query.text());
        return new Query(text, query.history(), query.context());
    }

    private RetrievalTraceCollector trace(Query query) {
        if (query == null || query.context() == null) {
            return null;
        }
        Object value = query.context().get(ProjectDocumentRetriever.CONTEXT_KEY);
        return value instanceof AuthorizedRetrievalContext context
                ? context.trace() : null;
    }

    private String sourceKey(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        Object documentId = metadata.get("documentId");
        Object chunkIndex = metadata.get("chunkIndex");
        if (documentId == null || String.valueOf(documentId).isBlank()
                || chunkIndex == null) {
            return null;
        }
        return String.valueOf(documentId).trim() + ":" + chunkIndex;
    }
}
