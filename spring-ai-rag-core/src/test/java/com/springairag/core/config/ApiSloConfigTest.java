package com.springairag.core.config;

import com.springairag.core.filter.ApiSloHandlerInterceptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ApiSloConfig unit tests.
 */
class ApiSloConfigTest {

    @Test
    void constructor_injectsInterceptor() {
        ApiSloHandlerInterceptor mockInterceptor = mock(ApiSloHandlerInterceptor.class);
        ApiSloConfig config = new ApiSloConfig(mockInterceptor);
        assertNotNull(config);
    }
}
