package com.springairag.core.observability;

/**
 * 低基数 HTTP 结果分类。
 */
public enum IntegrationHttpStatusClass {
    SUCCESS,
    CLIENT_ERROR,
    UNAUTHENTICATED,
    FORBIDDEN,
    CONFLICT,
    RATE_LIMITED,
    SERVER_ERROR,
    OTHER;

    public static IntegrationHttpStatusClass from(int status) {
        return switch (status) {
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 409 -> CONFLICT;
            case 429 -> RATE_LIMITED;
            default -> {
                if (status >= 200 && status <= 299) {
                    yield SUCCESS;
                }
                if (status == 400 || status == 404 || status == 405
                        || status == 408 || status == 413 || status == 415
                        || status == 422 || status == 425) {
                    yield CLIENT_ERROR;
                }
                if (status >= 500 && status <= 599) {
                    yield SERVER_ERROR;
                }
                yield OTHER;
            }
        };
    }
}
