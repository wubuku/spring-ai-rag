package com.springairag.core.controller;

import com.springairag.api.dto.DerivationRepairApplyRequest;
import com.springairag.api.dto.DerivationRepairPreviewRequest;
import com.springairag.api.dto.DerivationRepairPreviewResponse;
import com.springairag.api.dto.DerivationRepairStatusResponse;
import com.springairag.core.service.DerivationRepairService;
import com.springairag.core.versioning.ApiVersion;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 派生完整性修复控制面。 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag/collections/derivation-repairs")
public class DerivationRepairController {

    private final DerivationRepairService service;

    public DerivationRepairController(DerivationRepairService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    public DerivationRepairPreviewResponse preview(
            @Valid @RequestBody DerivationRepairPreviewRequest request) {
        return service.preview(request);
    }

    @PostMapping("/apply")
    public DerivationRepairStatusResponse apply(
            @Valid @RequestBody DerivationRepairApplyRequest request) {
        return service.apply(request);
    }

    @GetMapping("/{repairId}")
    public DerivationRepairStatusResponse status(@PathVariable UUID repairId) {
        return service.status(repairId);
    }
}
