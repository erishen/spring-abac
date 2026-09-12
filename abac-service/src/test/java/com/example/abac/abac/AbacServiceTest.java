package com.example.abac.abac;

import com.example.abac.abac.dto.AbacDtos;
import com.example.abac.abac.engine.PolicyEngine;
import com.example.abac.abac.model.Effect;
import com.example.abac.abac.model.Policy;
import com.example.abac.abac.repository.PolicyRepository;
import com.example.abac.abac.service.AbacService;
import com.example.abac.abac.service.PipClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AbacService 单元测试（不加载 Spring 上下文，离线可跑、确定性强）：
 * 用内存版 PolicyRepository 充当测试替身，覆盖默认拒绝、deny-override、决策缓存随策略变更失效、演示策略播种。
 * 请求都带全量资源属性，PIP 不会被触发，故 PipClient 用 mock。
 * 真实 JPA 映射 + 端到端链路由运行中的应用 + scripts/demo.sh 回归覆盖，这里只验证服务判定逻辑。
 */
class AbacServiceTest {

    /** 内存版 PolicyRepository：模拟 JPA 语义（save 分配 id、findAll 全量、findByName 精确匹配等）。 */
    private PolicyRepository fakeRepository() {
        PolicyRepository repo = mock(PolicyRepository.class);
        ConcurrentHashMap<Long, Policy> store = new ConcurrentHashMap<>();
        AtomicLong idGen = new AtomicLong(1);

        when(repo.save(any(Policy.class))).thenAnswer(inv -> {
            Policy p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(idGen.getAndIncrement());
            }
            store.put(p.getId(), p);
            return p;
        });
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(store.values()));
        when(repo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        when(repo.findByName(anyString())).thenAnswer(inv ->
                store.values().stream()
                        .filter(p -> p.getName().equals(inv.getArgument(0)))
                        .findFirst());
        when(repo.existsById(anyLong())).thenAnswer(inv -> store.containsKey(inv.getArgument(0)));
        doAnswer(inv -> store.remove(inv.getArgument(0))).when(repo).deleteById(anyLong());
        when(repo.count()).thenAnswer(inv -> (long) store.size());
        return repo;
    }

    private AbacService newService(PolicyRepository repo) {
        PipClient pip = mock(PipClient.class);
        return new AbacService(repo, new PolicyEngine(), pip, true);
    }

    @Test
    void seed_then_default_deny_for_unmatched() {
        PolicyRepository repo = fakeRepository();
        AbacService svc = newService(repo);
        svc.seedIfEmpty();
        assertThat(svc.listPolicies()).hasSize(40);

        // 匿名主体读审计域：仅 admin 可见（AUD-40），未匹配任何 PERMIT → 默认拒绝
        AbacDtos.DecisionRequest req = new AbacDtos.DecisionRequest(
                Map.of("username", "anonymous", "title", "engineer"),
                new AbacDtos.ResourceRef("AUDIT", "1", null),
                "READ", null);
        assertThat(svc.decide(req).permitted()).isFalse();
    }

    @Test
    void review_third_state_wins_over_permit_but_loses_to_deny() {
        PolicyRepository repo = fakeRepository();
        AbacService svc = newService(repo);
        // REVIEW 优先级高于 PERMIT：大额交易应转人工复核而非直接放行
        save(svc, Effect.PERMIT, "TRADE", "EXECUTE", "true", 12);
        save(svc, Effect.REVIEW, "TRADE", "EXECUTE", "resource.amount > 50000", 85);
        AbacDtos.DecisionRequest big = new AbacDtos.DecisionRequest(
                Map.of("username", "alice", "title", "engineer"),
                new AbacDtos.ResourceRef("TRADE", null,
                        Map.of("type", "TRADE", "amount", 60000,
                                "channel", "MOBILE", "region", "CN", "cumulativeAfter", 60000)),
                "EXECUTE", null);
        var review = svc.decide(big);
        assertThat(review.effect()).isEqualTo("REVIEW");
        assertThat(review.permitted()).isFalse();

        // 但 DENY 优先级仍高于 REVIEW：黑名单/超限类拒绝不因 REVIEW 翻案
        save(svc, Effect.DENY, "TRADE", "EXECUTE",
                "resource.channel == 'WEB' && resource.region != 'CN'", 75);
        AbacDtos.DecisionRequest webOverseas = new AbacDtos.DecisionRequest(
                Map.of("username", "carol", "title", "manager"),
                new AbacDtos.ResourceRef("TRADE", null,
                        Map.of("type", "TRADE", "amount", 60000,
                                "channel", "WEB", "region", "US", "cumulativeAfter", 60000)),
                "EXECUTE", null);
        var denied = svc.decide(webOverseas);
        assertThat(denied.effect()).isEqualTo("DENY");
        assertThat(denied.permitted()).isFalse();
    }

    @Test
    void deny_override_wins_over_permit() {
        PolicyRepository repo = fakeRepository();
        AbacService svc = newService(repo);
        // 两条都命中 action=* condition=true：一条 PERMIT、一条 DENY → deny-override 取 DENY
        save(svc, Effect.PERMIT, "*", "*", "true", 10);
        save(svc, Effect.DENY, "*", "*", "true", 20);
        AbacDtos.DecisionRequest req = new AbacDtos.DecisionRequest(
                Map.of("username", "alice"),
                new AbacDtos.ResourceRef("DOCUMENT", "1", null),
                "READ", null);
        var d = svc.decide(req);
        assertThat(d.permitted()).isFalse();
        assertThat(d.effect()).isEqualTo("DENY");
    }

    @Test
    void decision_cache_invalidated_on_policy_change() {
        PolicyRepository repo = fakeRepository();
        AbacService svc = newService(repo);
        AbacDtos.ResourceRef resource = new AbacDtos.ResourceRef("DOCUMENT", "1",
                Map.of("type", "DOCUMENT", "id", "1", "owner", "alice",
                        "department", "ENG", "classification", "INTERNAL",
                        "requiredClearance", 2, "status", "DRAFT"));
        Map<String, Object> subject = Map.of("username", "alice", "department", "ENG",
                "clearance", 2, "region", "CN", "title", "engineer");
        AbacDtos.DecisionRequest req = new AbacDtos.DecisionRequest(subject, resource, "READ", null);

        // 无策略：默认拒绝
        assertThat(svc.decide(req).permitted()).isFalse();

        // 新增一条给 alice 读本部门文档的 PERMIT → 应翻为 PERMIT（缓存已随策略变更失效）
        save(svc, Effect.PERMIT, "DOCUMENT", "READ",
                "resource.department == subject.department", 10);
        assertThat(svc.decide(req).permitted()).isTrue();
    }

    private void save(AbacService svc, Effect effect, String rt, String action,
                      String cond, int prio) {
        svc.createPolicy(new AbacDtos.CreatePolicyRequest(
                "P-test-" + rt + "-" + action + "-" + prio,
                "test", effect.name(), rt, action, cond, prio, true));
    }
}
