package com.springairag.core.config;

import com.springairag.core.filter.ApiSloHandlerInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ApiSloConfig unit tests.
 */
class ApiSloConfigTest {

    @Test
    void constructor_injectsInterceptor() {
        ApiSloHandlerInterceptor mockInterceptor = mock(ApiSloHandlerInterceptor.class);
        InterceptorRegistration registration = mock(InterceptorRegistration.class);
        InterceptorRegistry registry = mock(InterceptorRegistry.class);
        // addInterceptor() 的返回值要被接着 .addPathPatterns(...)，mock 默认返回
        // null 会直接 NPE——那条栈正好把这条用例原本"没跑过"的现状暴露了。
        when(registry.addInterceptor(any())).thenReturn(registration);

        new ApiSloConfig(mockInterceptor).addInterceptors(registry);

        // Batch 956：原来只有 assertNotNull(config)——构造函数只是把参数存起来，
        // 对一个当场 new 出来的对象断非空恒成立。名字说的是"注入了拦截器"，
        // 那就验它真的被注册、且挂在正确的路径上。
        verify(registry).addInterceptor(same(mockInterceptor));
        verify(registration).addPathPatterns("/api/**", "/v1/**");
        // 路径写错（比如漏掉 /v1/**）时 SLO 就静默漏采，这条会红
        verifyNoMoreInteractions(registration);
    }
}
