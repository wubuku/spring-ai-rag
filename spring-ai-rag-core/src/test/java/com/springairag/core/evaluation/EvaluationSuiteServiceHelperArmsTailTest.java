package com.springairag.core.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagRetrievalEvaluationRepository;
import com.springairag.core.evaluation.EvaluationSuiteRepository;
import com.springairag.core.service.ApiKeyManagementService;
import com.springairag.core.service.CollectionRetrievalScopeResolver;
import com.springairag.core.service.RetrievalEvaluationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * 评测套件服务助手长尾（Batch 744，JaCoCo 驱动）：readVariantKeys
 * 空配置回退 default、currentRevision 对缺失环境变量回退
 * "unknown"、writeJson 对不可序列化对象回退 "{}"。
 */
class EvaluationSuiteServiceHelperArmsTailTest {

    private EvaluationSuiteService service() {
        return new EvaluationSuiteService(
                mock(EvaluationSuiteRepository.class),
                mock(EvaluationSuiteDefinitionValidator.class),
                mock(CollectionRetrievalScopeResolver.class),
                mock(EvaluationCaseExecutor.class),
                mock(RetrievalEvaluationService.class),
                mock(EmbeddingProfileProvider.class),
                new ObjectMapper(),
                new RagProperties(),
                mock(ApiKeyManagementService.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readVariantKeysFallsBackToDefaultOnEmptyConfiguration()
            throws Exception {
        Method method = EvaluationSuiteService.class.getDeclaredMethod(
                "readVariantKeys",
                com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);
        var mapper = new ObjectMapper();

        var empty = (List<String>) method.invoke(service(),
                mapper.createObjectNode());
        var withKeys = (List<String>) method.invoke(service(),
                mapper.readTree("{\"variantKeys\":[\"a\",\"b\"]}"));

        assertEquals(List.of("default"), empty);
        assertEquals(List.of("a", "b"), withKeys);
    }

    @Test
    void currentRevisionFallsBackToUnknownWhenEnvMissing() throws Exception {
        Method method = EvaluationSuiteService.class
                .getDeclaredMethod("currentRevision");
        method.setAccessible(true);
        String revision = (String) method.invoke(service());
        if (System.getenv("GIT_COMMIT") == null) {
            Assertions.assertEquals("unknown", revision);
        } else {
            Assertions.assertFalse(revision.isBlank());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void writeJsonFallsBackToEmptyObjectOnUnserializableValue()
            throws Exception {
        Method method = EvaluationSuiteService.class.getDeclaredMethod(
                "writeJson", Object.class);
        method.setAccessible(true);

        assertEquals("{\"k\":\"v\"}",
                (String) method.invoke(service(), Map.of("k", "v")));

        // 自引用结构触发序列化异常 → "{}"。
        Map<String, Object> circular = new java.util.HashMap<>();
        circular.put("self", circular);
        assertEquals("{}", (String) method.invoke(service(), circular));
    }
}
