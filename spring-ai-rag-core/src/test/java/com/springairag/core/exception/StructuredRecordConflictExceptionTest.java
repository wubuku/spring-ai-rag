package com.springairag.core.exception;

import com.springairag.api.enums.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 结构化记录写冲突：错误码固定为 STRUCTURED_RECORD_CONFLICT 且保留原因链。 */
class StructuredRecordConflictExceptionTest {

    @Test
    void carriesTheStructuredRecordConflictErrorCode() {
        StructuredRecordConflictException error =
                new StructuredRecordConflictException("identity conflict");

        assertEquals(ErrorCode.STRUCTURED_RECORD_CONFLICT, error.getErrorCodeEnum());
        assertEquals("identity conflict", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void preservesTheCauseChain() {
        IllegalStateException cause = new IllegalStateException("serialization failed");
        StructuredRecordConflictException error =
                new StructuredRecordConflictException("cannot write record", cause);

        assertEquals(ErrorCode.STRUCTURED_RECORD_CONFLICT, error.getErrorCodeEnum());
        assertSame(cause, error.getCause());
    }
}
