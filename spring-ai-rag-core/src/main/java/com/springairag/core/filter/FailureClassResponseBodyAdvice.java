package com.springairag.core.filter;

import com.springairag.api.dto.ErrorResponse;
import com.springairag.api.enums.FailureClass;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Adds the stable failure-class header to controller-produced native errors. */
@RestControllerAdvice
public class FailureClassResponseBodyAdvice implements ResponseBodyAdvice<ErrorResponse> {

    @Override
    public boolean supports(
            MethodParameter returnType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        if (ErrorResponse.class.isAssignableFrom(returnType.getParameterType())) {
            return true;
        }
        Class<?> genericBodyType = ResolvableType.forMethodParameter(returnType)
                .getGeneric(0)
                .resolve();
        return genericBodyType != null && ErrorResponse.class.isAssignableFrom(genericBodyType);
    }

    @Override
    public ErrorResponse beforeBodyWrite(
            ErrorResponse body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body == null) {
            return null;
        }
        FailureClass failureClass = body.getFailureClass();
        if (failureClass == null) {
            failureClass = FailureClass.from(body.getStatus(), body.getError());
            body.setFailureClass(failureClass);
        }
        response.getHeaders().set(FailureClassHttpSupport.HEADER, failureClass.name());
        return body;
    }
}
