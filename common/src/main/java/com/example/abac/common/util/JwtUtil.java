package com.example.abac.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 零依赖 HS256 JWT 工具：仅用 JDK 的 Mac + Base64 + Spring Boot 自带的 jackson。
 * 结构完全符合 RFC 7519（header.payload.signature），任何标准 JWT 库均可校验。
 *
 * 与 RBAC 版的差别：payload 多挂一个 {@code attrs} 对象，承载 ABAC 所需的主体属性
 * （dept / clearance / region / title ...）。PDP 判定时不再回头查库，
 * JWT 本身就是主体属性的载体（网关解析后随决策请求一起发给 PDP）。
 *
 * 抽自 auth-service 与 gateway-service 的逐字节相同实现，统一到 common 避免改一处漏一处。
 */
public class JwtUtil {

    private final String secret;
    private final long ttlMillis;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String ALG_HS256 = "HS256";

    public JwtUtil(String secret, long ttlMillis) {
        this.secret = secret;
        this.ttlMillis = ttlMillis;
    }

    /** 只带 subject 的令牌（注册后首次签发等场景）。 */
    public String generate(String username) {
        return generate(username, Map.of());
    }

    /** 带主体属性的令牌：attrs 会原样出现在 payload.attrs 中。 */
    public String generate(String username, Map<String, Object> attrs) {
        long now = System.currentTimeMillis();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", ALG_HS256);
        header.put("typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", username);
        payload.put("iat", now / 1000);
        payload.put("exp", (now + ttlMillis) / 1000);
        payload.put("attrs", attrs == null ? Map.of() : attrs);

        String h = b64(toJson(header));
        String p = b64(toJson(payload));
        String sig = b64(hmac((h + "." + p).getBytes(StandardCharsets.UTF_8)));
        return h + "." + p + "." + sig;
    }

    /** 校验签名与过期时间，返回 subject + 主体属性；失败抛 JwtException。 */
    public Claims verify(String token) {
        if (token == null || token.isEmpty()) {
            throw new JwtException("empty token");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new JwtException("malformed token");
        }
        String sigInput = parts[0] + "." + parts[1];
        String expected = b64(hmac(sigInput.getBytes(StandardCharsets.UTF_8)));
        if (!constantTimeEquals(expected, parts[2])) {
            throw new JwtException("bad signature");
        }
        try {
            Map<String, Object> payload =
                    mapper.readValue(fromB64(parts[1]), new TypeReference<Map<String, Object>>() {
                    });
            Object exp = payload.get("exp");
            if (exp instanceof Number && ((Number) exp).longValue() * 1000 < System.currentTimeMillis()) {
                throw new JwtException("token expired");
            }
            Object sub = payload.get("sub");
            if (sub == null) {
                throw new JwtException("missing subject");
            }
            Map<String, Object> attrs = Map.of();
            Object raw = payload.get("attrs");
            if (raw instanceof Map<?, ?> m && !m.isEmpty()) {
                attrs = mapper.convertValue(raw, new TypeReference<Map<String, Object>>() {
                });
            }
            return new Claims(sub.toString(), attrs);
        } catch (Exception e) {
            throw new JwtException("invalid payload");
        }
    }

    /** 校验结果：subject 即 username，attrs 即 ABAC 主体属性。 */
    public record Claims(String subject, Map<String, Object> attrs) {
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String b64(String s) {
        return b64(s.getBytes(StandardCharsets.UTF_8));
    }

    private String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private byte[] fromB64(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ab.length != bb.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < ab.length; i++) {
            result |= ab[i] ^ bb[i];
        }
        return result == 0;
    }
}
