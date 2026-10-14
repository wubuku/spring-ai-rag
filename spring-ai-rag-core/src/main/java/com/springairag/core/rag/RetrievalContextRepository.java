package com.springairag.core.rag;

import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.retrieval.KeywordIndexSqlScope;
import com.springairag.core.retrieval.RetrievalScopeSql;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class RetrievalContextRepository {
    private final JdbcTemplate jdbc;
    private final EmbeddingProfileProvider profiles;
    private final DocumentDerivationDescriptorProvider descriptors;

    public RetrievalContextRepository(JdbcTemplate jdbc, EmbeddingProfileProvider profiles,
            DocumentDerivationDescriptorProvider descriptors) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.descriptors = descriptors;
    }

    public List<Chunk> load(List<Seed> seeds, AuthorizedRetrievalContext context,
            int radius, int maxCharacters) {
        if (seeds.isEmpty()) {
            return List.of();
        }
        if (seeds.size() > 40 || radius < 0 || radius > 2 || maxCharacters < 256 || maxCharacters > 16000) {
            throw new IllegalArgumentException("Invalid context lookup limits");
        }
        List<Object> args = new ArrayList<>();
        for (Seed seed : seeds) {
            args.add(seed.ordinal());
            args.add(seed.documentId());
            args.add(seed.chunkIndex());
            args.add(seed.text());
        }
        var scope = RetrievalScopeSql.build(context.scope(), context.filters());
        args.addAll(scope.args());
        args.add(radius);
        args.add(radius);
        args.add(maxCharacters);
        String freshness = KeywordIndexSqlScope.fromAndFreshness(profiles.getActiveProfile().id(),
                descriptors.textDescriptor().chunkerVersion(), descriptors.jsonRecordDescriptor().chunkerVersion())
                .replace("WHERE s.local_index_status", "JOIN seeds ON seeds.document_id = e.document_id "
                        + "AND seeds.chunk_index = e.chunk_index AND seeds.text = e.chunk_text WHERE s.local_index_status");
        String sql = "WITH seeds(ordinal, document_id, chunk_index, text) AS (VALUES "
                + String.join(",", Collections.nCopies(seeds.size(),
                        "(CAST(? AS integer), CAST(? AS bigint), CAST(? AS integer), CAST(? AS text))"))
                + "), anchors AS (SELECT seeds.ordinal, e.* "
                + freshness
                + "AND d.source_deleted_at IS NULL AND d.document_type IS DISTINCT FROM 'json-record' "
                + scope.sql()
                + ") SELECT anchors.ordinal, neighbor.document_id, neighbor.local_index_generation, "
                + "neighbor.content_hash, neighbor.chunker_version, neighbor.chunk_index, "
                + "neighbor.chunk_start_pos, neighbor.chunk_end_pos, neighbor.chunk_text "
                + "FROM anchors JOIN rag_document_chunks neighbor ON neighbor.document_id = anchors.document_id "
                + "AND neighbor.local_index_generation = anchors.local_index_generation "
                + "AND neighbor.content_hash = anchors.content_hash AND neighbor.chunker_version = anchors.chunker_version "
                + "AND neighbor.chunk_index::bigint BETWEEN anchors.chunk_index::bigint - ? AND anchors.chunk_index::bigint + ? "
                + "WHERE length(neighbor.chunk_text) <= ? ORDER BY anchors.ordinal, neighbor.chunk_index";
        return jdbc.query(sql, (row, rowNumber) -> new Chunk(row.getInt("ordinal"), row.getLong("document_id"),
                row.getLong("local_index_generation"), row.getString("content_hash"), row.getString("chunker_version"),
                row.getInt("chunk_index"), row.getInt("chunk_start_pos"), row.getInt("chunk_end_pos"),
                row.getString("chunk_text")), args.toArray());
    }

    public record Seed(int ordinal, long documentId, int chunkIndex, String text) { }
    public record Chunk(int ordinal, long documentId, long generation, String hash, String chunker,
                        int index, int start, int end, String text) { }
}
