package com.example.abac.agent.service;

import com.example.abac.agent.model.ToolCallRequest;
import com.example.abac.agent.service.AbacClient.Decision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentServiceTest {

    private final AbacClient abac = mock(AbacClient.class);
    private final AgentService svc = new AgentService(abac, 10);

    private Decision decision(String effect) {
        return new Decision(effect, "PERMIT".equals(effect), 1L, "P-12", "reason-" + effect, List.of());
    }

    private ToolCallRequest call(String toolType, String operation, Map<String, Object> params) {
        return new ToolCallRequest("research-agent", "high", toolType, operation, params);
    }

    private Map<String, Object> subject() {
        return Map.of("username", "alice", "title", "engineer",
                "agentId", "research-agent", "trust", "high");
    }

    @Test
    void permit_whitelisted_fetch_records_and_accumulates_session() {
        when(abac.decide(any(), any(), eq("FETCH"))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> resource = inv.getArgument(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> attrs = (Map<String, Object>) resource.get("attributes");
            // 域名已解析，且第一次会话累计为 0
            assertThat(attrs.get("urlDomain")).isEqualTo("example.com");
            assertThat((Number) attrs.get("sessionFetchCount")).isEqualTo(0);
            return decision("PERMIT");
        });
        var r = svc.execute(call("WEB", "FETCH", Map.of("url", "https://example.com/page")),
                subject(), "sess-1");
        assertThat(r.effect()).isEqualTo("PERMIT");
        assertThat(r.execution().summary()).contains("example.com");
        // 第二次：会话累计应为 1
        when(abac.decide(any(), any(), eq("FETCH"))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> resource = inv.getArgument(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> attrs = (Map<String, Object>) resource.get("attributes");
            assertThat((Number) attrs.get("sessionFetchCount")).isEqualTo(1);
            return decision("PERMIT");
        });
        svc.execute(call("WEB", "FETCH", Map.of("url", "https://example.com/other")),
                subject(), "sess-1");
        assertThat(svc.sessionStats("sess-1").get("fetchCount")).isEqualTo(2);
    }

    @Test
    void deny_blocks_without_executing() {
        when(abac.decide(any(), any(), eq("EXECUTE"))).thenReturn(decision("DENY"));
        var r = svc.execute(call("CODE", "EXECUTE", Map.of("code", "Runtime.getRuntime().exec('rm -rf /')")),
                subject(), "sess-1");
        assertThat(r.effect()).isEqualTo("DENY");
        assertThat(r.execution()).isNull();
        assertThat(svc.myExecutions("alice")).isEmpty();
    }

    @Test
    void review_queues_then_approve_executes() {
        when(abac.decide(any(), any(), eq("SEND"))).thenReturn(decision("REVIEW"));
        var r = svc.execute(call("EMAIL", "SEND", Map.of("recipientCount", 6)),
                subject(), "sess-1");
        assertThat(r.effect()).isEqualTo("REVIEW");
        assertThat(r.reviewId()).isNotNull();
        // REVIEW 不执行、不累计
        assertThat(svc.myExecutions("alice")).isEmpty();

        var approved = svc.approveReview(r.reviewId(), "admin");
        assertThat(approved.execution().effect()).isEqualTo("REVIEW_APPROVED");
        assertThat(svc.myExecutions("alice")).hasSize(1);

        // 复核任务只能处理一次
        assertThatThrownBy(() -> svc.approveReview(r.reviewId(), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已处理");
    }

    @Test
    void session_limit_visible_in_attributes() {
        when(abac.decide(any(), any(), eq("FETCH"))).thenReturn(decision("PERMIT"));
        for (int i = 0; i < 3; i++) {
            svc.execute(call("WEB", "FETCH", Map.of("url", "https://example.com/" + i)),
                    subject(), "sess-2");
        }
        Map<String, Object> stats = svc.sessionStats("sess-2");
        assertThat(stats.get("fetchCount")).isEqualTo(3);
        assertThat(stats.get("limitFetch")).isEqualTo(10);
    }
}
