package com.springairag.core.controller;

import com.springairag.api.dto.EvaluationCompareResponse;
import com.springairag.api.dto.EvaluationRunCreateRequest;
import com.springairag.api.dto.EvaluationRunResponse;
import com.springairag.api.dto.EvaluationSuiteCreateRequest;
import com.springairag.api.dto.EvaluationSuiteResponse;
import com.springairag.api.dto.EvaluationSuiteVersionCreateRequest;
import com.springairag.api.dto.EvaluationSuiteVersionResponse;
import com.springairag.core.evaluation.EvaluationSuiteService;
import com.springairag.core.versioning.ApiVersion;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@ApiVersion("v1")
@RequestMapping("/rag/evaluation")
public class EvaluationSuiteController {

    private final EvaluationSuiteService service;

    public EvaluationSuiteController(EvaluationSuiteService service) {
        this.service = service;
    }

    @PostMapping("/suites")
    public EvaluationSuiteResponse createSuite(
            @Valid @RequestBody EvaluationSuiteCreateRequest request) {
        return service.createSuite(request);
    }

    @GetMapping("/suites")
    public List<EvaluationSuiteResponse> listSuites() {
        return service.listSuites();
    }

    @GetMapping("/suites/{suiteKey}")
    public EvaluationSuiteResponse getSuite(@PathVariable String suiteKey) {
        return service.getSuite(suiteKey);
    }

    @PostMapping("/suites/{suiteKey}/versions")
    public EvaluationSuiteVersionResponse createVersion(
            @PathVariable String suiteKey,
            @Valid @RequestBody EvaluationSuiteVersionCreateRequest request) {
        return service.createVersion(suiteKey, request);
    }

    @PostMapping("/runs")
    public EvaluationRunResponse createRun(
            @Valid @RequestBody EvaluationRunCreateRequest request) {
        return service.createRun(request);
    }

    @GetMapping("/runs/{runId}")
    public EvaluationRunResponse getRun(@PathVariable UUID runId) {
        return service.getRun(runId);
    }

    @GetMapping("/runs/compare")
    public EvaluationCompareResponse compare(
            @RequestParam UUID leftRunId,
            @RequestParam UUID rightRunId) {
        return service.compare(leftRunId, rightRunId);
    }
}
