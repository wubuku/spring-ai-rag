package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * 数据擦除状态查询的“不存在”边界。
 *
 * <p>`JdbcTemplate.queryForObject` 在无行时抛 {@link EmptyResultDataAccessException}，
 * 它不是业务异常，冒泡到统一异常处理器就是一个 500。契约声明 404，所以 `load`
 * 必须把它翻译成 {@link ErrorCode#NOT_FOUND}。这里同时覆盖 `status` 与 `apply`，
 * 因为两者都经由 `load` —— 只测其中一个，另一条路径改坏了不会被发现。
 */
class DataErasureServiceNotFoundTest {

    private JdbcTemplate jdbcTemplate;
    private DataErasureService service;

    @BeforeEach
    void setUp() {
        // 默认答案而不是 when(...) 桩：queryForObject 有多个重载，按签名桩容易
        // 落到另一个重载上而静默返回 null——那会让这个测试看上去“没 404”。
        jdbcTemplate = mock(JdbcTemplate.class, invocation -> {
            if ("queryForObject".equals(invocation.getMethod().getName())) {
                throw new EmptyResultDataAccessException(1);
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        service = new DataErasureService(
                jdbcTemplate,
                new ObjectMapper(),
                mock(AuditLogService.class),
                mock(PlatformTransactionManager.class));
    }

    @Test
    void status_unknownOperation_returnsNotFoundInsteadOfServerError() {
        UUID operationId = UUID.randomUUID();

        RagException error = assertThrows(RagException.class,
                () -> service.status(operationId, mock(HttpServletRequest.class)));

        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void apply_unknownOperation_returnsNotFoundInsteadOfServerError() {
        UUID operationId = UUID.randomUUID();
        var request = new com.springairag.api.dto.DataErasureApplyRequest(
                operationId, "token", "fingerprint");

        RagException error = assertThrows(RagException.class,
                () -> service.apply(request, mock(HttpServletRequest.class)));

        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCodeEnum());
    }
}
