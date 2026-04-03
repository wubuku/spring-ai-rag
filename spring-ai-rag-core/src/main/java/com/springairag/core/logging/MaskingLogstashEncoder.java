package com.springairag.core.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import net.logstash.logback.encoder.LogstashEncoder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * LogstashEncoder subclass that applies sensitive data masking to log messages
 * before JSON encoding. This ensures sensitive data (passwords, API keys, tokens)
 * is never serialized into JSON logs.
 *
 * <p>Usage in logback-spring.xml:</p>
 * <pre>{@code
 * <encoder class="com.springairag.core.logging.MaskingLogstashEncoder">
 *   ...
 * </encoder>
 * }</pre>
 *
 * @see SensitiveDataMaskingConverter for the underlying masking logic
 */
public class MaskingLogstashEncoder extends LogstashEncoder {

    @Override
    public byte[] encode(ILoggingEvent event) {
        String formattedMessage = event.getFormattedMessage();
        if (formattedMessage != null && !formattedMessage.isEmpty()) {
            String maskedMessage = SensitiveDataMaskingConverter.maskSensitiveData(formattedMessage);
            if (!maskedMessage.equals(formattedMessage)) {
                // Use dynamic proxy to wrap the event and return masked message
                ILoggingEvent maskedEvent = (ILoggingEvent) Proxy.newProxyInstance(
                        ILoggingEvent.class.getClassLoader(),
                        new Class<?>[] { ILoggingEvent.class },
                        new MaskedInvocationHandler(event, maskedMessage)
                );
                return super.encode(maskedEvent);
            }
        }
        return super.encode(event);
    }

    /**
     * InvocationHandler that intercepts getFormattedMessage() and getMessage()
     * to return the masked version, while delegating all other method calls
     * to the original event.
     */
    private static class MaskedInvocationHandler implements InvocationHandler {

        private final ILoggingEvent delegate;
        private final String maskedMessage;

        MaskedInvocationHandler(ILoggingEvent delegate, String maskedMessage) {
            this.delegate = delegate;
            this.maskedMessage = maskedMessage;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            // Intercept message-retrieval methods
            if ("getFormattedMessage".equals(methodName) || "getMessage".equals(methodName)) {
                return maskedMessage;
            }
            return method.invoke(delegate, args);
        }
    }
}
