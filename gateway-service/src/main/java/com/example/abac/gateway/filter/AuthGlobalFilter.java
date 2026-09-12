package com.example.abac.gateway.filter;

import com.example.abac.common.util.JwtException;
import com.example.abac.common.util.JwtUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * API 网关（PEP 策略执行点，Spring Cloud Gateway GlobalFilter）：
 *  1. 校验 JWT，拿到 username 与<b>主体属性</b>（dept/clearance/region/title）；
 *  2. 把路径 + 方法翻译成（资源类型 / 动作 / 资源 id）三元组；
 *  3. 带上环境属性（小时 / 星期）问 PDP：POST abac-service /api/decide；
 *     PDP 只有资源 id 时会经 PIP 回源补齐资源属性；
 *  4. PERMIT 才放行，并把 X-User / X-Attr-* 注入下游（业务服务据此做行级过滤）；
 *  5. 裁决后【异步、best-effort】发射审计事件到 audit-service（fail-open，不影响业务）；
 *  6. 轻量链路追踪：生成/透传 X-Trace-Id。
 *
 * 内部服务只注册在 Eureka，外部只能打到网关这一道门。
 */
@Component
@Order(-1)
public class AuthGlobalFilter implements GlobalFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 网关内部直连审计服务的私有头，audit-service 据此拒绝外部伪造写入。 */
    private static final String AUDIT_HEADER = "X-Internal-Audit";
    private static final String AUDIT_HEADER_VALUE = "gateway";

    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String USER_HEADER = "X-User";
    private static final String ATTR_DEPARTMENT = "X-Attr-Department";
    private static final String ATTR_CLEARANCE = "X-Attr-Clearance";
    private static final String ATTR_REGION = "X-Attr-Region";
    private static final String ATTR_TITLE = "X-Attr-Title";

    private final JwtUtil jwtUtil;
    private final WebClient lbWebClient;
    private final CircuitBreaker pdpCircuitBreaker;

    /** 路径 + 方法 → 资源类型与动作的映射结果。 */
    private record ActionMapping(String resourceType, String action) {
    }

    public AuthGlobalFilter(@Value("${app.jwt-secret}") String secret,
                            @Value("${app.jwt-ttl:86400000}") long ttl,
                            @Qualifier("lbWebClientBuilder") WebClient.Builder lbWebClientBuilder,
                            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.jwtUtil = new JwtUtil(secret, ttl);
        this.lbWebClient = lbWebClientBuilder.build();
        this.pdpCircuitBreaker = circuitBreakerRegistry.circuitBreaker("abac-decide");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod().name();

        String traceId = resolveTraceId(exchange);
        exchange.getResponse().getHeaders().set(TRACE_HEADER, traceId);
        ServerWebExchange traced = exchange.mutate().request(
                exchange.getRequest().mutate().header(TRACE_HEADER, traceId).build()
        ).build();

        // 公开路由：登录/注册/健康检查，直接放行
        if (path.equals("/api/login") || path.equals("/api/register")
                || path.equals("/health") || path.startsWith("/actuator")) {
            return chain.filter(traced);
        }
        if (!path.startsWith("/api/")) {
            return chain.filter(traced);
        }

        // 1) 认证
        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            emitAudit(traceId, null, "auth:missing", method, path, null, "DENY", 401, null);
            return unauthorized(exchange, "missing bearer token");
        }
        JwtUtil.Claims claims;
        try {
            claims = jwtUtil.verify(auth.substring(7));
        } catch (JwtException e) {
            emitAudit(traceId, null, "auth:invalid", method, path, null, "DENY", 401, null);
            return unauthorized(exchange, e.getMessage());
        }

        Map<String, Object> subject = new HashMap<>();
        subject.put("username", claims.subject());
        if (claims.attrs() != null) {
            subject.putAll(claims.attrs());
        }

        // 身份与主体属性注入下游（业务服务据此做行级过滤，不必回查 auth）
        ServerWebExchange authed = traced.mutate().request(r -> {
            r.header(USER_HEADER, claims.subject());
            r.header(ATTR_DEPARTMENT, str(subject.get("department")));
            r.header(ATTR_CLEARANCE, str(subject.get("clearance")));
            r.header(ATTR_REGION, str(subject.get("region")));
            r.header(ATTR_TITLE, str(subject.get("title")));
        }).build();

        // 2) 映射出 (资源类型 / 动作)
        ActionMapping mapping = mapAction(path, method);
        if (mapping == null) {
            // /api/me、/api/decide 等：已登录即可，不做策略门禁
            emitAudit(traceId, claims.subject(), method + " " + path, method, path,
                    extractResourceId(path), "ALLOW", null, null);
            return chain.filter(authed);
        }
        Long resourceId = extractResourceId(path);

        // 3) 问 PDP
        Map<String, Object> body = new HashMap<>();
        body.put("subject", subject);
        Map<String, Object> resource = new HashMap<>();
        resource.put("type", mapping.resourceType());
        resource.put("id", resourceId == null ? null : String.valueOf(resourceId));
        body.put("resource", resource);
        body.put("action", mapping.action());
        body.put("environment", environment(exchange));

        return lbWebClient.post()
                .uri("lb://abac-service/api/decide")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .transformDeferred(CircuitBreakerOperator.of(pdpCircuitBreaker))
                .flatMap(resp -> {
                    boolean allowed = Boolean.TRUE.equals(resp.get("permitted"));
                    String action = mapping.resourceType() + ":" + mapping.action();
                    if (allowed) {
                        emitAudit(traceId, claims.subject(), action, method, path, resourceId,
                                "ALLOW", null, resp);
                        return chain.filter(authed.mutate().request(
                                r -> r.header("X-Decision", "PERMIT")).build());
                    }
                    emitAudit(traceId, claims.subject(), action, method, path, resourceId,
                            "DENY", 403, resp);
                    return forbidden(exchange, "denied by policy: "
                            + String.valueOf(resp.get("reason")));
                })
                .onErrorResume(e -> {
                    emitAudit(traceId, claims.subject(),
                            mapping.resourceType() + ":" + mapping.action(), method, path,
                            resourceId, "DENY", 403,
                            Map.of("reason", "pdp degraded (" + pdpCircuitBreaker.getState() + ")"));
                    return forbidden(exchange, "denied: pdp degraded (circuit "
                            + pdpCircuitBreaker.getState() + "): " + e.getClass().getSimpleName());
                });
    }

    /** 环境属性：判定所需的时间上下文（PDP 侧缺省也会生成，这里显式带上来源 IP）。 */
    private Map<String, Object> environment(ServerWebExchange exchange) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> env = new HashMap<>();
        env.put("hour", now.getHour());
        env.put("dayOfWeek", now.getDayOfWeek().name());
        String ip = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        env.put("ip", ip == null || ip.isBlank() ? "127.0.0.1" : ip.split(",")[0].trim());
        return env;
    }

    private String resolveTraceId(ServerWebExchange exchange) {
        String id = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
        return id;
    }

    /** 把 HTTP 路径 + 方法映射到（资源类型，动作）；返回 null 表示"仅需登录"。 */
    private ActionMapping mapAction(String path, String method) {
        String[] seg = path.split("/");
        if (seg.length < 3) {
            return null;
        }
        // 集合请求（GET 且 URL 里没有资源 id）用 LIST 动作，与单条 READ 区分开。
        // 原因：列表接口拿不到具体资源属性，若仍按 READ 问 PDP，所有依赖 resource.*
        // 的策略都不命中 → default deny → 列表全 403。集合的真正安全边界在业务服务
        // 的行级过滤（列表放行不等于每行都可见）。
        boolean isCollection = seg.length <= 3 || seg[3].isBlank();

        return switch (seg[2]) {
            case "documents" -> {
                // /api/documents/{id}/publish 单独识别为一个动作
                if (seg.length >= 5 && "publish".equalsIgnoreCase(seg[4])) {
                    yield new ActionMapping("DOCUMENT", "PUBLISH");
                }
                yield new ActionMapping("DOCUMENT", switch (method) {
                    case "GET" -> isCollection ? "LIST" : "READ";
                    case "POST" -> "CREATE";
                    case "PUT" -> "UPDATE";
                    case "DELETE" -> "DELETE";
                    default -> "READ";
                });
            }
            case "users" -> new ActionMapping("USER",
                    "GET".equals(method) ? (isCollection ? "LIST" : "READ") : "UPDATE");
            case "policies" -> new ActionMapping("POLICY", switch (method) {
                case "GET" -> isCollection ? "LIST" : "READ";
                case "POST" -> "CREATE";
                case "PUT" -> "UPDATE";
                case "DELETE" -> "DELETE";
                default -> "READ";
            });
            case "audit" -> new ActionMapping("AUDIT", isCollection ? "LIST" : "READ");
            case "trades" -> {
                // /api/trades（POST 下单 → EXECUTE，由 risk-service 调 PDP 做风控决策）；
                // /api/trades/reviews/**（复核）与 /api/trades/stats 只做基础门禁，
                // 复核的管理员权限在 risk-service 业务层校验（title ∈ manager/admin）。
                String sub = seg.length > 3 ? seg[3] : "";
                if ("reviews".equalsIgnoreCase(sub) || "stats".equalsIgnoreCase(sub)) {
                    yield new ActionMapping("TRADE", "GET".equals(method) ? "LIST" : "EXECUTE");
                }
                yield new ActionMapping("TRADE", "POST".equals(method) ? "EXECUTE" : "LIST");
            }
            case "agent" -> {
                // /api/agent/tools（POST 工具调用 → TOOL/EXECUTE 网关兜底 P-15 放行，
                // 细粒度工具域校验由 agent-service 按 WEB/EMAIL/... 再问 PDP）；
                // /api/agent/reviews、/api/agent/session 只做基础门禁。
                String sub = seg.length > 3 ? seg[3] : "";
                if ("reviews".equalsIgnoreCase(sub) || "session".equalsIgnoreCase(sub)) {
                    yield new ActionMapping("TOOL", "GET".equals(method) ? "LIST" : "EXECUTE");
                }
                yield new ActionMapping("TOOL", "POST".equals(method) ? "EXECUTE" : "LIST");
            }
            default -> null; // me / decide：仅需登录
        };
    }

    /** 从路径中解析资源 id（如 /api/documents/12/publish -> 12）。 */
    private Long extractResourceId(String path) {
        String[] seg = path.split("/");
        for (int i = 3; i < seg.length; i++) {
            if (seg[i].matches("\\d+")) {
                try {
                    return Long.parseLong(seg[i]);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 异步、best-effort 发射审计事件（含命中的策略），失败只记日志。 */
    private void emitAudit(String traceId, String actor, String action, String method, String path,
                           Long resourceId, String decision, Integer status, Map<String, Object> pdp) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("traceId", traceId);
            body.put("actor", actor == null ? "anonymous" : actor);
            body.put("action", action);
            body.put("method", method);
            body.put("path", path);
            body.put("resourceId", resourceId);
            body.put("decision", decision);
            body.put("status", status);
            body.put("detail", pdp == null ? "" : String.valueOf(pdp.getOrDefault("reason", "")));
            if (pdp != null) {
                body.put("policyId", pdp.get("policyId"));
                body.put("policyName", pdp.get("policyName"));
                body.put("reason", pdp.get("reason"));
            }
            lbWebClient.post()
                    .uri("lb://audit-service/api/audit")
                    .header(AUDIT_HEADER, AUDIT_HEADER_VALUE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .subscribe(v -> {
                    }, e -> log.warn("audit emit failed (ignored): {}", e.getMessage()));
        } catch (Exception e) {
            log.warn("audit emit error (ignored): {}", e.getMessage());
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 统一错误响应。用 ObjectMapper 序列化，避免手拼 JSON 遇到反斜杠/换行等字符时产出非法 JSON。 */
    private Mono<Void> writeJson(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            body = JSON.writeValueAsBytes(Map.of("error", message == null ? "" : message));
        } catch (JsonProcessingException e) {
            body = "{\"error\":\"internal error\"}".getBytes(StandardCharsets.UTF_8);
        }
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        return writeJson(exchange, HttpStatus.UNAUTHORIZED, message);
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String message) {
        return writeJson(exchange, HttpStatus.FORBIDDEN, message);
    }
}
