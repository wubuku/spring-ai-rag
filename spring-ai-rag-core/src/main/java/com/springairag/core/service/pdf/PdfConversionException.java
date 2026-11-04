package com.springairag.core.service.pdf;

/**
 * PDF 转换失败的类型化异常。
 *
 * <p>转换器不再用 {@code false} 表达所有失败：调用方需要区分“文件损坏/加密/
 * 无文字层/超出预算”，才能返回明确错误而不是统一 500。
 */
public class PdfConversionException extends RuntimeException {

    /** 稳定失败原因，供上层映射为用户可见语义。 */
    public enum Reason {
        INVALID_PDF,
        ENCRYPTED_PDF,
        NO_EXTRACTABLE_TEXT,
        TOO_MANY_PAGES,
        OUTPUT_TOO_LARGE,
        CONVERSION_FAILED
    }

    private final Reason reason;

    public PdfConversionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public PdfConversionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
