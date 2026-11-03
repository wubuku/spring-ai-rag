package com.springairag.core.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.CollectionExportResponse;
import com.springairag.core.entity.RagCollection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 流式导出 Collection：按主键游标分批读取文档，边读边写 JSON。
 *
 * <p>内存占用与文档总数无关，只与单批大小相关；响应的 JSON 结构与
 * {@link CollectionExportResponse} 完全一致，调用方无需切换解析逻辑。
 * 分批游标是 {@code id > afterId} 而非 offset，避免深分页退化为全表扫描。
 */
@Service
public class CollectionExportService {

    /** 单批文档数。200 条足以摊薄往返开销，又不会在大文档场景撑大堆。 */
    public static final int EXPORT_BATCH_SIZE = 200;

    private static final String EXPORT_COLUMNS = """
            SELECT id, title, source, content, document_type, metadata, size,
                   external_id, source_namespace, source_revision,
                   source_deleted_at, jsonb_payload, original_filename, enabled
            FROM rag_documents
            """;

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>
            METADATA_TYPE = new com.fasterxml.jackson.core.type.TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CollectionExportService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 给定文档数时计划的分块数；用于压测脚本断言流式导出确实分批。 */
    public static int plannedBatchCount(long documentCount) {
        if (documentCount <= 0) {
            return 0;
        }
        return (int) ((documentCount + EXPORT_BATCH_SIZE - 1) / EXPORT_BATCH_SIZE);
    }

    /**
     * 将 Collection 及其文档写入 {@code out}，不关闭输出流（由 Web 容器负责）。
     */
    public void write(RagCollection collection, long documentCount, OutputStream out)
            throws IOException {
        Instant exportedAt = Instant.now();
        JsonGenerator generator = objectMapper.getFactory().createGenerator(out);
        // 输出流由 Web 容器管理；这里只 flush，不关闭。
        generator.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
        try {
            generator.writeStartObject();
            writeField(generator, "name", collection.getName());
            writeField(generator, "collectionKey", collection.getCollectionKey());
            writeField(generator, "description", collection.getDescription());
            writeField(generator, "embeddingModel", collection.getEmbeddingModel());
            writeField(generator, "dimensions", collection.getDimensions());
            writeField(generator, "enabled", collection.getEnabled());
            writeField(generator, "metadata", collection.getMetadata());

            generator.writeFieldName("documents");
            generator.writeStartArray();
            long afterId = 0L;
            long remaining = documentCount;
            while (remaining > 0) {
                int limit = (int) Math.min(EXPORT_BATCH_SIZE, remaining);
                List<ExportRow> rows = readBatch(collection.getId(), afterId, limit);
                if (rows.isEmpty()) {
                    // 计数后并发删除导致剩余不足：按快照语义提前结束。
                    break;
                }
                for (ExportRow row : rows) {
                    objectMapper.writeValue(generator, row.summary());
                }
                afterId = rows.get(rows.size() - 1).id();
                remaining -= rows.size();
                if (rows.size() < limit) {
                    break;
                }
            }
            generator.writeEndArray();

            writeField(generator, "exportedAt", exportedAt);
            generator.writeNumberField("documentCount", (int) documentCount);
            generator.writeEndObject();
            generator.flush();
        } finally {
            generator.close();
        }
    }

    private void writeField(JsonGenerator generator, String name, Object value)
            throws IOException {
        generator.writeFieldName(name);
        objectMapper.writeValue(generator, value);
    }

    /**
     * 读取一批文档。子类/测试可覆写此方法，避免单测依赖真实数据库。
     */
    protected List<ExportRow> readBatch(long collectionId, long afterId, int limit) {
        return jdbcTemplate.query(
                EXPORT_COLUMNS + " WHERE collection_id = ? AND id > ? ORDER BY id LIMIT ?",
                (rs, rowNum) -> mapRow(rs),
                collectionId, afterId, limit);
    }

    private ExportRow mapRow(ResultSet rs) throws SQLException {
        return new ExportRow(
                rs.getLong("id"),
                new CollectionExportResponse.ExportedDocumentSummary(
                        rs.getString("title"),
                        rs.getString("source"),
                        rs.getString("content"),
                        rs.getString("document_type"),
                        toMap(rs.getString("metadata")),
                        nullableLong(rs, "size"),
                        rs.getString("external_id"),
                        rs.getString("source_namespace"),
                        rs.getString("source_revision"),
                        rs.getObject("source_deleted_at", LocalDateTime.class),
                        toJson(rs.getString("jsonb_payload")),
                        rs.getString("original_filename"),
                        nullableBoolean(rs, "enabled")));
    }

    private Map<String, Object> toMap(String json) throws SQLException {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, METADATA_TYPE);
        } catch (IOException error) {
            throw new SQLException("Invalid document metadata JSON", error);
        }
    }

    private JsonNode toJson(String json) throws SQLException {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (IOException error) {
            throw new SQLException("Invalid document jsonb payload", error);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean nullableBoolean(ResultSet rs, String column)
            throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    /** 一批中一行的主键与导出摘要；主键用于游标翻页。 */
    public record ExportRow(long id,
                            CollectionExportResponse.ExportedDocumentSummary summary) {
    }
}
