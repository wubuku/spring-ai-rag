package com.springairag.core.rag;

import com.springairag.core.config.RagChatProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.IntStream;

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
        String references = IntStream.range(0, documents.size())
                .mapToObj(index -> "[S" + (index + 1) + "] "
                        + documents.get(index).getText())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
        String text = """
                请仅依据以下参考资料回答用户问题。无法从资料确认的内容要明确说明。

                参考资料：
                %s

                用户问题：
                %s
                """.formatted(references, query.text());
        return new Query(text, query.history(), query.context());
    }
}
