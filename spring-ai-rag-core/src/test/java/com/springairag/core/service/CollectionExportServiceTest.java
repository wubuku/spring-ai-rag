package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.springairag.api.dto.CollectionExportResponse;
import com.springairag.core.entity.RagCollection;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CollectionExportServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void streamsLargeCollectionInBoundedBatches() throws Exception {
        List<CollectionExportService.ExportRow> rows =
                rows(1L, 1_000, "body");
        RecordingExportService service = new RecordingExportService(rows);

        CollectionExportResponse response = write(service,
                collection(), rows.size());

        assertEquals(1_000, response.documentCount());
        assertEquals(1_000, response.documents().size());
        assertEquals(CollectionExportService.plannedBatchCount(1_000),
                service.batchCount);
        assertEquals(CollectionExportService.EXPORT_BATCH_SIZE,
                service.largestBatch);
        assertTrue(service.batches.stream()
                .allMatch(batch -> batch <= CollectionExportService
                        .EXPORT_BATCH_SIZE));
    }

    @Test
    void keepsExistingJsonShapeForEmptyCollection() throws Exception {
        RecordingExportService service =
                new RecordingExportService(new ArrayList<>());

        CollectionExportResponse response = write(service, collection(), 0);

        assertEquals(0, response.documentCount());
        assertTrue(response.documents().isEmpty());
        assertEquals(0, service.batchCount);
    }

    @Test
    void plannedBatchCountRoundsUpAndHandlesZero() {
        assertEquals(0, CollectionExportService.plannedBatchCount(0));
        assertEquals(1, CollectionExportService.plannedBatchCount(1));
        assertEquals(1, CollectionExportService.plannedBatchCount(200));
        assertEquals(2, CollectionExportService.plannedBatchCount(201));
        assertEquals(5, CollectionExportService.plannedBatchCount(1_000));
    }

    private CollectionExportResponse write(
            CollectionExportService service,
            RagCollection collection,
            long documentCount) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.write(collection, documentCount, out);
        return objectMapper.readValue(out.toByteArray(),
                CollectionExportResponse.class);
    }

    private RagCollection collection() {
        RagCollection collection = new RagCollection();
        collection.setId(1L);
        collection.setName("scale");
        collection.setCollectionKey("scale-key");
        collection.setDescription("export fixture");
        collection.setEmbeddingModel("bge-m3");
        collection.setDimensions(1024);
        collection.setEnabled(true);
        return collection;
    }

    private static List<CollectionExportService.ExportRow> rows(
            long startId, int count, String content) {
        List<CollectionExportService.ExportRow> rows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long id = startId + index;
            rows.add(new CollectionExportService.ExportRow(id,
                    new CollectionExportResponse.ExportedDocumentSummary(
                            "Doc " + id, "source.txt", content, "TEXT",
                            null, 10L, null, null, null, null, null, null,
                            true)));
        }
        return rows;
    }

    private final class RecordingExportService extends CollectionExportService {

        private final List<ExportRow> rows;
        private final List<Integer> batches = new ArrayList<>();
        private int batchCount;
        private int largestBatch;

        private RecordingExportService(List<ExportRow> rows) {
            super(mock(JdbcTemplate.class),
                    CollectionExportServiceTest.this.objectMapper);
            this.rows = rows;
        }

        @Override
        protected List<ExportRow> readBatch(long collectionId, long afterId,
                                            int limit) {
            List<ExportRow> batch = rows.stream()
                    .filter(row -> row.id() > afterId)
                    .limit(limit)
                    .toList();
            batchCount++;
            largestBatch = Math.max(largestBatch, batch.size());
            batches.add(batch.size());
            return batch;
        }
    }
}
