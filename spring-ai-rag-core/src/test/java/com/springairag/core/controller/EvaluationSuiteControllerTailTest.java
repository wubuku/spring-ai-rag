package com.springairag.core.controller;

import com.springairag.api.dto.EvaluationSuiteResponse;
import com.springairag.api.dto.EvaluationSuiteVersionResponse;
import com.springairag.core.evaluation.EvaluationSuiteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EvaluationSuiteController 委托长尾（Batch 688，JaCoCo 驱动）：
 * createSuite / listSuites / getSuite / createVersion 的委托路径。
 */
class EvaluationSuiteControllerTailTest {

    private EvaluationSuiteService service;
    private EvaluationSuiteController controller;

    @BeforeEach
    void setUp() {
        service = mock(EvaluationSuiteService.class);
        controller = new EvaluationSuiteController(service);
    }

    @Test
    void createSuiteDelegatesToService() {
        var request = mock(com.springairag.api.dto.EvaluationSuiteCreateRequest.class);
        var expected = mock(EvaluationSuiteResponse.class);
        when(service.createSuite(request)).thenReturn(expected);

        var result = controller.createSuite(request);

        assertEquals(expected, result);
    }

    @Test
    void listSuitesDelegatesToService() {
        var expected = List.of(
                mock(EvaluationSuiteResponse.class),
                mock(EvaluationSuiteResponse.class));
        when(service.listSuites()).thenReturn(expected);

        var result = controller.listSuites();

        assertEquals(2, result.size());
    }

    @Test
    void getSuiteDelegatesToService() {
        var expected = mock(EvaluationSuiteResponse.class);
        when(service.getSuite("my-suite")).thenReturn(expected);

        var result = controller.getSuite("my-suite");

        assertEquals(expected, result);
    }

    @Test
    void createVersionDelegatesToService() {
        var request = mock(com.springairag.api.dto.EvaluationSuiteVersionCreateRequest.class);
        var expected = mock(EvaluationSuiteVersionResponse.class);
        when(service.createVersion("my-suite", request)).thenReturn(expected);

        var result = controller.createVersion("my-suite", request);

        assertEquals(expected, result);
    }
}
