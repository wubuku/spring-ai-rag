package com.springairag.core.entity;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagAuditLog entity.
 */
class RagAuditLogTest {

    @Test
    void defaultConstructor_works() {
        RagAuditLog log = new RagAuditLog();
        assertNotNull(log);
        assertNull(log.getId());
    }

    @Test
    void allFields_setAndGet() {
        ZonedDateTime now = ZonedDateTime.now();

        RagAuditLog log = new RagAuditLog();
        log.setId(1L);
        log.setOperation("CREATE");
        log.setEntityType("Collection");
        log.setEntityId("42");
        log.setSessionId("sess-789");
        log.setOperator("api-key-***");
        log.setDescription("Created collection test-collection");
        log.setDetails("{\"name\":\"test-collection\",\"dimensions\":1024}");
        log.setClientIp("192.168.1.100");
        log.setTraceId("abc123def456");
        log.setCreatedAt(now);

        assertEquals(1L, log.getId());
        assertEquals("CREATE", log.getOperation());
        assertEquals("Collection", log.getEntityType());
        assertEquals("42", log.getEntityId());
        assertEquals("sess-789", log.getSessionId());
        assertEquals("api-key-***", log.getOperator());
        assertEquals("Created collection test-collection", log.getDescription());
        assertTrue(log.getDetails().contains("test-collection"));
        assertEquals("192.168.1.100", log.getClientIp());
        assertEquals("abc123def456", log.getTraceId());
        assertEquals(now, log.getCreatedAt());
    }

    @Test
    void onCreate_setsCreatedAtWhenNull() {
        RagAuditLog log = new RagAuditLog();
        assertNull(log.getCreatedAt());
        log.onCreate();
        assertNotNull(log.getCreatedAt());
    }

    @Test
    void onCreate_preservesExistingCreatedAt() {
        ZonedDateTime existing = ZonedDateTime.now().minusDays(1);
        RagAuditLog log = new RagAuditLog();
        log.setCreatedAt(existing);
        log.onCreate();
        assertEquals(existing, log.getCreatedAt());
    }

    @Test
    void operationTypes() {
        RagAuditLog log = new RagAuditLog();
        log.setOperation("CREATE");
        assertEquals("CREATE", log.getOperation());

        log.setOperation("UPDATE");
        assertEquals("UPDATE", log.getOperation());

        log.setOperation("DELETE");
        assertEquals("DELETE", log.getOperation());

        log.setOperation("READ");
        assertEquals("READ", log.getOperation());
    }

    @Test
    void entityTypes() {
        RagAuditLog log = new RagAuditLog();
        log.setEntityType("Collection");
        assertEquals("Collection", log.getEntityType());

        log.setEntityType("Document");
        assertEquals("Document", log.getEntityType());

        log.setEntityType("ChatHistory");
        assertEquals("ChatHistory", log.getEntityType());
    }

    @Test
    void optionalFields_canBeNull() {
        RagAuditLog log = new RagAuditLog();
        log.setSessionId(null);
        log.setOperator(null);
        log.setDescription(null);
        log.setDetails(null);
        log.setClientIp(null);
        log.setTraceId(null);

        assertNull(log.getSessionId());
        assertNull(log.getOperator());
        assertNull(log.getDescription());
        assertNull(log.getDetails());
        assertNull(log.getClientIp());
        assertNull(log.getTraceId());
    }

    @Test
    void details_jsonFormat() {
        RagAuditLog log = new RagAuditLog();
        log.setDetails("{\"before\":{\"name\":\"old\"},\"after\":{\"name\":\"new\"}}");
        assertTrue(log.getDetails().contains("old"));
        assertTrue(log.getDetails().contains("new"));
    }

    @Test
    void clientIp_variousFormats() {
        RagAuditLog log = new RagAuditLog();

        log.setClientIp("127.0.0.1");
        assertEquals("127.0.0.1", log.getClientIp());

        log.setClientIp("192.168.1.100");
        assertEquals("192.168.1.100", log.getClientIp());

        log.setClientIp("2001:0db8:85a3:0000:0000:8a2e:0370:7334");
        assertEquals("2001:0db8:85a3:0000:0000:8a2e:0370:7334", log.getClientIp());
    }

    @Test
    void traceId_canBeAnyString() {
        RagAuditLog log = new RagAuditLog();
        log.setTraceId("req-abc-123-xyz");
        assertEquals("req-abc-123-xyz", log.getTraceId());
    }
}
