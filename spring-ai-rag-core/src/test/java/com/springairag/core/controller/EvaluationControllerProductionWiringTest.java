package com.springairag.core.controller;

import com.springairag.api.dto.SemanticEvaluationRequest;
import com.springairag.api.dto.SemanticEvaluationResponse;
import com.springairag.core.evaluation.SemanticEvaluationService;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.RetrievalEvaluationService;
import com.springairag.core.service.UserFeedbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EvaluationController 按生产接线构造的用例（Batch 821）。
 *
 * <p>{@code SemanticEvaluationService} 是无条件 {@code @Service}，而
 * {@code setSemanticEvaluationService} 用的是
 * {@code @Autowired(required = false)}——同样的假可选声明。
 * {@code scripts/verify-false-optional-wiring.mjs} 最初没看见它，
 * 因为那个 setter **没有 public 修饰符**；这是该门禁的第四处静默盲区。
 *
 * <p>本类取代两个已删除的用例：它们断言
 * {@code IllegalStateException("Semantic evaluation is not available")}，
 * 而那个状态在运行的应用里不会出现——它们的作用是挪动覆盖率数字。
 */
class EvaluationControllerProductionWiringTest {

    private SemanticEvaluationService semanticEvaluationService;
    private EvaluationController controller;

    @BeforeEach
    void setUp() {
        semanticEvaluationService = mock(SemanticEvaluationService.class);
        // Batch 850：它从包私有的 required=false setter 变成了必填构造器参数。
        controller = new EvaluationController(
                mock(RetrievalEvaluationService.class),
                mock(UserFeedbackService.class),
                mock(AuditLogService.class),
                semanticEvaluationService);
    }

    @Test
    void semanticEvaluationServiceIsWiredUnderProductionWiring() throws Exception {
        var field = EvaluationController.class.getDeclaredField("semanticEvaluationService");
        field.setAccessible(true);

        // Batch 850 更新了这句话。原文写的是"为 null 就说明守卫可达"，
        // 而那个守卫在 Batch 822 就删了，**那句话本身已经不成立**。
        // 现在的形态更硬：它是必填构造器参数，容器要么装配上、要么启动失败，
        // 所以"为 null"只可能是有人手写了一个漏传参数的构造调用。
        assertNotNull(field.get(controller),
                "semanticEvaluationService is null although it is a required constructor "
                        + "dependency, so this construction site is missing an argument");
    }

    @Test
    void semanticDelegatesToTheService() {
        SemanticEvaluationRequest request = new SemanticEvaluationRequest(
                "FACT_CHECKING", "q", "ctx", "answer", "test/model");
        SemanticEvaluationResponse expected = mock(SemanticEvaluationResponse.class);
        when(semanticEvaluationService.evaluate(request)).thenReturn(expected);

        ResponseEntity<SemanticEvaluationResponse> response = controller.semantic(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(expected, response.getBody());
    }

    @Test
    void semanticBatchDelegatesToTheService() {
        List<SemanticEvaluationRequest> requests = List.of(
                new SemanticEvaluationRequest("FACT_CHECKING", "q1", "c1", "a1", "test/model"),
                new SemanticEvaluationRequest("FACT_CHECKING", "q2", "c2", "a2", "test/model"));
        when(semanticEvaluationService.evaluateBatch(requests)).thenReturn(List.of());

        ResponseEntity<List<SemanticEvaluationResponse>> response =
                controller.semanticBatch(requests);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(), response.getBody());
    }
}
