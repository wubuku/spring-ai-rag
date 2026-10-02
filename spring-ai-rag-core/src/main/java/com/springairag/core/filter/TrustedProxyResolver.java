package com.springairag.core.filter;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * 可信代理解析器：只有在直连对端本身可信时，才采信 {@code X-Forwarded-For}。
 *
 * <p>背景：本项目的 API Key 加固计划把"通过假 {@code X-Forwarded-For} 绕过
 * pre-auth IP limiter"列为**必须防御的攻击**（计划 4.2 第 7 条），
 * 风险表把"直接信任 X-Forwarded-For"标为**高**风险，缓解措施写的是
 * <em>trusted proxy resolver</em>。信任边界一节同时写明"所有 HTTP Header
 * 不可信"。在解析器落地之前，过滤器是**无条件**采信该头的：
 * 默认 {@code strategy=ip} 下，客户端每次换一个伪造的 {@code X-Forwarded-For}
 * 就会拿到一个全新的计数窗口，限流形同虚设。
 *
 * <p>规则（fail closed）：
 * <ol>
 *   <li>直连对端（{@code request.getRemoteAddr()}）不在可信集合内 →
 *       **完全忽略** {@code X-Forwarded-For}，直接用对端地址；</li>
 *   <li>在可信集合内 → 从右往左剥离可信代理，返回**第一个不可信**的地址；
 *       整条链都可信时返回最左侧那一个。</li>
 * </ol>
 *
 * <p>默认没有任何可信代理（{@code trusted-proxies} 为空），也就是默认**不**采信
 * {@code X-Forwarded-For}。这是有意的：把安全默认设成"信任"等于把限流绕过
 * 交给部署者记得配置。反过来代价是——部署在反向代理后面却没配置
 * {@code trusted-proxies} 时，所有请求会算在代理 IP 上、共享一个窗口。
 * 该取舍与迁移说明写在 {@code docs/configuration*.md}。
 *
 * <p>支持三种写法：精确地址（{@code 192.168.1.5}）、
 * IPv4 CIDR（{@code 10.0.0.0/8}）、IPv6 CIDR（{@code 2001:db8::/32}）。
 * 解析失败一律抛 {@link IllegalArgumentException}，由启动期的
 * {@code validateTopology()} 转成快速失败，而不是等到第一个请求才暴露。
 */
public final class TrustedProxyResolver {

    /** 永远不接受任何代理链：默认构造出来的实例。 */
    public static final TrustedProxyResolver TRUST_NONE = new TrustedProxyResolver(List.of());

    private final List<Cidr> trusted;

    private TrustedProxyResolver(List<Cidr> trusted) {
        this.trusted = List.copyOf(trusted);
    }

    /**
     * @param patterns 形如 {@code 10.0.0.0/8}、{@code 192.168.1.5}、{@code ::1} 的条目
     * @throws IllegalArgumentException 任何一条无法解析
     */
    public static TrustedProxyResolver of(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return TRUST_NONE;
        }
        List<Cidr> parsed = new ArrayList<>(patterns.size());
        for (String raw : patterns) {
            if (raw == null) {
                throw new IllegalArgumentException(
                        "rag.rate-limit.trusted-proxies must not contain null entries");
            }
            String pattern = raw.trim();
            if (pattern.isEmpty()) {
                continue;
            }
            parsed.add(Cidr.parse(pattern));
        }
        return new TrustedProxyResolver(parsed);
    }

    public boolean trustsNothing() {
        return trusted.isEmpty();
    }

    /** 该地址是否属于可信代理集合。无法解析的地址一律当作不可信。 */
    public boolean isTrusted(String address) {
        byte[] candidate = toBytes(address);
        if (candidate == null) {
            return false;
        }
        for (Cidr cidr : trusted) {
            if (cidr.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析真实客户端地址。
     *
     * @param remoteAddress  直连对端（{@code request.getRemoteAddr()}）
     * @param forwardedFor   {@code X-Forwarded-For} 原始头值，可为 null
     * @return 用于限流计数的客户端标识
     */
    public String resolveClientAddress(String remoteAddress, String forwardedFor) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return "";
        }
        String peer = remoteAddress.trim();
        if (forwardedFor == null || forwardedFor.isBlank() || !isTrusted(peer)) {
            return peer;
        }
        String[] hops = forwardedFor.split(",");
        String candidate = peer;
        // 从右往左：每剥离一个可信代理，剩下的就是它告诉我们的上一跳。
        for (int i = hops.length - 1; i >= 0; i--) {
            candidate = hops[i].trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        // 整条链都是可信代理：最左侧那个就是它自己。
        return hops[0].trim();
    }

    private static byte[] toBytes(String address) {
        if (address == null) {
            return null;
        }
        String value = address.trim();
        if (value.isEmpty()) {
            return null;
        }
        // 去掉 IPv6 的方括号形式，例如 [::1]:8080
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0) {
                return null;
            }
            value = value.substring(1, end);
        }
        try {
            // 字面量地址不做 DNS 解析：getByName 遇到主机名会走解析器，
            // 那正是限流路径上不该有的阻塞与不确定性。
            if (!isIpLiteral(value)) {
                return null;
            }
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean isIpLiteral(String value) {
        if (value.indexOf(':') >= 0) {
            return true;
        }
        boolean digits = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                digits = false;
                break;
            }
        }
        return digits && !value.isEmpty();
    }

    /** 一条可信代理规则：网络地址 + 前缀长度。 */
    private record Cidr(byte[] network, int prefixBits) {

        static Cidr parse(String pattern) {
            int slash = pattern.indexOf('/');
            String addressPart = slash < 0 ? pattern : pattern.substring(0, slash);
            byte[] address = toBytes(addressPart);
            if (address == null) {
                throw new IllegalArgumentException(
                        "rag.rate-limit.trusted-proxies contains an address that is not an IP "
                                + "literal: " + pattern);
            }
            int maxBits = address.length * 8;
            int prefix;
            if (slash < 0) {
                prefix = maxBits;
            } else {
                try {
                    prefix = Integer.parseInt(pattern.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "rag.rate-limit.trusted-proxies contains a non-numeric prefix: " + pattern);
                }
            }
            if (prefix < 0 || prefix > maxBits) {
                throw new IllegalArgumentException(
                        "rag.rate-limit.trusted-proxies prefix is out of range: " + pattern);
            }
            return new Cidr(mask(address, prefix), prefix);
        }

        boolean contains(byte[] candidate) {
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }

        private static byte[] mask(byte[] address, int prefixBits) {
            byte[] result = address.clone();
            int fullBytes = prefixBits / 8;
            int remainingBits = prefixBits % 8;
            for (int i = fullBytes; i < result.length; i++) {
                result[i] = 0;
            }
            if (remainingBits != 0 && fullBytes < result.length) {
                result[fullBytes] = (byte) (result[fullBytes] & ((0xFF << (8 - remainingBits)) & 0xFF));
            }
            return result;
        }
    }
}
