package com.springairag.api.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentProcessingRunOriginTest {

    @Test
    void normalizesLegacyJobOriginsToStableValues() {
        assertEquals(DocumentProcessingRunOrigin.LOCAL_CREATE,
                DocumentProcessingRunOrigin.fromRaw("BATCH_CREATE"));
        assertEquals(DocumentProcessingRunOrigin.DOCUMENT_MUTATION,
                DocumentProcessingRunOrigin.fromRaw("LOCAL_VERSION_RESTORE"));
        assertEquals(DocumentProcessingRunOrigin.EXTERNAL_SYNC,
                DocumentProcessingRunOrigin.fromRaw("JSON_RECORD_UPSERT"));
        assertEquals(DocumentProcessingRunOrigin.API,
                DocumentProcessingRunOrigin.fromRaw("API_DUPLICATE_FORCE"));
        assertEquals(DocumentProcessingRunOrigin.OTHER,
                DocumentProcessingRunOrigin.fromRaw("provider-internal"));
    }
}
