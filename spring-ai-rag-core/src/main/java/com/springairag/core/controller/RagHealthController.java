package com.springairag.core.controller;

import com.springairag.core.versioning.ApiVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查控制器
 */
@RestController
@ApiVersion("v1")
@RequestMapping("/rag")
@Tag(name = "RAG Health", description = "健康检查与状态监控")
public class RagHealthController {

    private final JdbcTemplate jdbcTemplate;

    public RagHealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 健康检查
     */
    @Operation(summary = "健康检查", description = "检查数据库连接状态、文档/嵌入向量统计。")
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("timestamp", Instant.now().toString());

        // 检查数据库连接
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            result.put("database", "UP");
        } catch (Exception e) { // Health endpoint: must not throw
            result.put("database", "DOWN");
            result.put("databaseError", e.getMessage());
        }

        // 检查表数量
        try {
            Integer docCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM rag_documents", Integer.class);
            Integer embCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM rag_embeddings", Integer.class);
            result.put("documents", docCount);
            result.put("embeddings", embCount);
        } catch (Exception e) { // Health endpoint: tables may not exist
            result.put("tablesError", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }
}
