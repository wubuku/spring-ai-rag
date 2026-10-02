package com.springairag.core.chat;

import com.springairag.core.filter.ApiKeyAuthFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Batch 816。{@code ChatPrincipal.from(null)} 的默认行为。
 *
 * <p>它**不抛异常**，而是回落到 {@code local()}。这是一条 fail-open 默认值：
 * 任何把 {@code HttpServletRequest} 丢成 {@code null} 的调用方，
 * 拿到的不是错误，而是一个看起来合法的身份。
 *
 * <p>和 {@code ApiKeyCollectionAccess.isUnrestricted(null)} 一样，它对
 * auth 关闭的本地部署是必需的——那时根本没有 API key 策略。
 * 但正因为它不报错，危险完全落在调用方：Batch 815 与 816 删掉的六个重载
 * 全都是靠这条回落"安静地"拿到无作用域数据。
 * {@code verify-null-request-forwarding.mjs} 负责挡住那类调用方。
 */
@DisplayName("ChatPrincipal 对缺失请求上下文的回落行为")
class ChatPrincipalNullRequestTest {

    @Test
    @DisplayName("from(null) 回落到 local()，不抛异常")
    void nullRequestFallsBackToLocal() {
        ChatPrincipal principal = ChatPrincipal.from(null);

        assertNotNull(principal);
        assertEquals("local:auth-disabled", principal.id());
        assertEquals("AUTH_DISABLED", principal.type());
        assertFalse(principal.admin(),
                "回落身份不应带管理员权限——否则 null 会变成提权而不是降级");
    }

    @Test
    @DisplayName("fromCurrentRequest 在没有绑定请求时也走同一条回落")
    void fromCurrentRequestWithoutBindingFallsBackToLocal() {
        assertEquals(ChatPrincipal.from(null),
                ChatPrincipal.fromCurrentRequest());
    }

    @Test
    @DisplayName("带真实请求时按 principal 类型派生，null 回落不参与")
    void realRequestDerivesByPrincipalType() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "key-a");

        ChatPrincipal principal = ChatPrincipal.from(request);

        assertEquals("db:key-a", principal.id());
        assertEquals(ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                principal.type());
    }

    @Test
    @DisplayName("请求存在但没有认证属性时同样回落，而不是当成已认证")
    void authenticatedRequestWithoutAttributesFallsBack() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertEquals(ChatPrincipal.from(null), ChatPrincipal.from(request));
    }
}
