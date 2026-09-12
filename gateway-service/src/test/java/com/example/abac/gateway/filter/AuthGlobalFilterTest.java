package com.example.abac.gateway.filter;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthGlobalFilter（网关 PEP）认证边界单测。
 * 保护点：公开路由必须放行；/api 未带或伪造 Bearer token 必须 401；
 * PDP 调用分支（带有效 token）由 document 侧 AbacClient 测试覆盖同源契约。
 */
@ExtendWith(MockitoExtension.class)
class AuthGlobalFilterTest {

    @Mock
    private GatewayFilterChain chain;

    private AuthGlobalFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuthGlobalFilter(
                "test-secret-for-gateway-tests", 86_400_000L,
                WebClient.builder(), CircuitBreakerRegistry.ofDefaults());
    }

    /** 放行分支才会走到 chain.filter；401 分支直接短路，不 stub。 */
    private void allowChain() {
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private MockServerWebExchange exchange(String method, String path, String bearer) {
        MockServerHttpRequest.BaseBuilder<?> req = switch (method) {
            case "GET" -> MockServerHttpRequest.get(path);
            case "POST" -> MockServerHttpRequest.post(path);
            default -> MockServerHttpRequest.get(path);
        };
        if (bearer != null) {
            req.header("Authorization", "Bearer " + bearer);
        }
        return MockServerWebExchange.from(req.build());
    }

    private void assertCompleted(MockServerWebExchange exchange) {
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
    }

    // ---------- 公开路由：无 token 也必须放行 ----------

    @Test
    void loginRouteIsPublic() {
        allowChain();
        MockServerWebExchange exchange = exchange("POST", "/api/login", null);
        assertCompleted(exchange);
        verify(chain).filter(any());
    }

    @Test
    void registerRouteIsPublic() {
        allowChain();
        MockServerWebExchange exchange = exchange("POST", "/api/register", null);
        assertCompleted(exchange);
        verify(chain).filter(any());
    }

    @Test
    void healthRouteIsPublic() {
        allowChain();
        MockServerWebExchange exchange = exchange("GET", "/health", null);
        assertCompleted(exchange);
        verify(chain).filter(any());
    }

    @Test
    void nonApiRouteIsPublic() {
        allowChain();
        MockServerWebExchange exchange = exchange("GET", "/index.html", null);
        assertCompleted(exchange);
        verify(chain).filter(any());
    }

    // ---------- 认证拒绝：/api 必须校验 token ----------

    @Test
    void apiWithoutTokenReturns401() {
        MockServerWebExchange exchange = exchange("GET", "/api/documents", null);
        assertCompleted(exchange);
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void apiWithMalformedTokenReturns401() {
        MockServerWebExchange exchange = exchange("GET", "/api/documents", "not.a.jwt");
        assertCompleted(exchange);
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void apiWithGarbageTokenReturns401() {
        MockServerWebExchange exchange = exchange("GET", "/api/documents",
                "eyJhbGciOiJIUzI1NiJ9.invalid.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertCompleted(exchange);
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }
}
