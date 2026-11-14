package com.springairag.core.tenant;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * WP8：回调 HMAC-SHA256 签名与 constant-time 验签。
 */
public final class TenantCallbackSignature {

    private TenantCallbackSignature() {
    }

    public static String sign(
            String secret,
            String operation,
            String providerName,
            String providerTaskId,
            Instant receivedAt,
            String nonce,
            String bodyStatus,
            String bodyResultUrl) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String message = canonical(operation, providerName, providerTaskId,
                    receivedAt, nonce, bodyStatus, bodyResultUrl);
            return HexFormat.of().formatHex(
                    mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("cannot sign callback", error);
        }
    }

    public static boolean verify(String secret, TenantCallbackRequest request) {
        if (secret == null || request == null || request.signature() == null) {
            return false;
        }
        try {
            byte[] expected = HexFormat.of().parseHex(sign(
                    secret, request.operation(), request.providerName(),
                    request.providerTaskId(), request.receivedAt(), request.nonce(),
                    request.bodyStatus(), request.bodyResultUrl()));
            byte[] provided = HexFormat.of().parseHex(request.signature());
            return MessageDigest.isEqual(expected, provided);
        } catch (IllegalArgumentException invalidHex) {
            return false;
        }
    }

    private static String canonical(
            String operation, String providerName, String providerTaskId,
            Instant receivedAt, String nonce, String bodyStatus, String bodyResultUrl) {
        return List.of(operation, providerName, providerTaskId,
                receivedAt.toString(), nonce, value(bodyStatus), value(bodyResultUrl))
                .stream()
                .map(v -> v.length() + ":" + v)
                .collect(Collectors.joining());
    }

    private static String value(String v) {
        return v == null ? "" : v;
    }
}
