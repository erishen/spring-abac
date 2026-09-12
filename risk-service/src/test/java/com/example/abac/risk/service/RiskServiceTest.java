package com.example.abac.risk.service;

import com.example.abac.risk.model.TradeRequest;
import com.example.abac.risk.service.AbacClient.Decision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskServiceTest {

    private final AbacClient abac = mock(AbacClient.class);
    private final RiskService svc = new RiskService(abac, 10);

    private Decision decision(String effect) {
        return new Decision(effect, "PERMIT".equals(effect), 1L, "P-12", "reason-" + effect, List.of());
    }

    @Test
    void permit_executes_and_accumulates() {
        when(abac.decide(any(), any(), eq("EXECUTE"))).thenReturn(decision("PERMIT"));
        Map<String, Object> subject = Map.of("username", "alice", "title", "engineer");

        var r1 = svc.execute(new TradeRequest(1000, "MOBILE", "CN", "AAPL"), subject);
        assertThat(r1.effect()).isEqualTo("PERMIT");
        assertThat(r1.execution()).isNotNull();

        // 第二次：累计金额应为 2000（cumulativeAfter 进资源属性包验证）
        when(abac.decide(any(), any(), eq("EXECUTE"))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> resource = inv.getArgument(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> attrs = (Map<String, Object>) resource.get("attributes");
            assertThat((Number) attrs.get("cumulativeAmount")).isEqualTo(1000d);
            assertThat((Number) attrs.get("cumulativeAfter")).isEqualTo(2000d);
            return decision("PERMIT");
        });
        var r2 = svc.execute(new TradeRequest(1000, "MOBILE", "CN", "AAPL"), subject);
        assertThat(r2.permitted()).isTrue();
        assertThat(svc.stats("alice").get("amountTotal")).isEqualTo(2000d);
        assertThat(svc.stats("alice").get("count")).isEqualTo(2);
    }

    @Test
    void deny_rejects_without_accumulating() {
        when(abac.decide(any(), any(), eq("EXECUTE"))).thenReturn(decision("DENY"));
        var r = svc.execute(new TradeRequest(999999, "WEB", "US", "00700"),
                Map.of("username", "carol", "title", "manager"));
        assertThat(r.effect()).isEqualTo("DENY");
        assertThat(r.execution()).isNull();
        assertThat(svc.stats("carol").get("count")).isEqualTo(0);
    }

    @Test
    void review_queues_without_accumulating_then_approve_accumulates() {
        when(abac.decide(any(), any(), eq("EXECUTE"))).thenReturn(decision("REVIEW"));
        Map<String, Object> subject = Map.of("username", "alice", "title", "engineer");

        var r = svc.execute(new TradeRequest(60000, "MOBILE", "CN", "AAPL"), subject);
        assertThat(r.effect()).isEqualTo("REVIEW");
        assertThat(r.reviewId()).isNotNull();
        // REVIEW 不累计
        assertThat(svc.stats("alice").get("amountTotal")).isEqualTo(0d);

        // 批准 → 转成交并累计
        var approved = svc.approveReview(r.reviewId(), "admin");
        assertThat(approved.execution().effect()).isEqualTo("REVIEW_APPROVED");
        assertThat(svc.stats("alice").get("amountTotal")).isEqualTo(60000d);
        assertThat(svc.myTrades("alice")).hasSize(1);

        // 再次批准同一单应报错（复核任务只能处理一次）
        assertThatThrownBy(() -> svc.approveReview(r.reviewId(), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已处理");
    }
}
