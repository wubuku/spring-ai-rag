package com.springairag.core.filter;

import com.springairag.api.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class FailureClassResponseBodyAdviceTest {

    @Test
    void addsHeaderBeforeErrorBodyIsCommitted() {
        FailureClassResponseBodyAdvice advice = new FailureClassResponseBodyAdvice();
        ErrorResponse body = ErrorResponse.builder()
                .error("VALIDATION_FAILED")
                .status(HttpStatus.BAD_REQUEST.value())
                .message("invalid")
                .build();
        ServletServerHttpResponse response =
                new ServletServerHttpResponse(new MockHttpServletResponse());

        ErrorResponse result = advice.beforeBodyWrite(
                body,
                null,
                MediaType.APPLICATION_PROBLEM_JSON,
                MappingJackson2HttpMessageConverter.class,
                null,
                response);

        assertThat(result.getFailureClass()).hasToString("CLIENT_REQUEST");
        assertThat(response.getHeaders().getFirst(FailureClassHttpSupport.HEADER))
                .isEqualTo("CLIENT_REQUEST");
    }
}
