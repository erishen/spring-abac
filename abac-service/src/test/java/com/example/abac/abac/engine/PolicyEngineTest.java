package com.example.abac.abac.engine;

import com.example.abac.abac.model.Effect;
import com.example.abac.abac.model.Policy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略引擎单测（纯单元测试，不起 Spring 上下文）。
 * 覆盖：deny-override、优先级、作用域匹配、无条件策略、默认拒绝、
 * 表达式异常不崩、危险表达式被拦截、环境属性参与判定。
 */
class PolicyEngineTest {

    private final PolicyEngine engine = new PolicyEngine();

    private static Policy policy(long id, String name, Effect effect, String resourceType,
                                 String action, String condition, int priority) {
        Policy p = new Policy();
        p.setId(id);
        p.setName(name);
        p.setEffect(effect);
        p.setResourceType(resourceType);
        p.setAction(action);
        p.setCondition(condition);
        p.setPriority(priority);
        p.setEnabled(true);
        return p;
    }

    private static Map<String, Object> subject(String username, String dept, int clearance,
                                               String region, String title) {
        return Map.of("username", username, "department", dept, "clearance", clearance,
                "region", region, "title", title);
    }

    private static Map<String, Object> document(String owner, String dept, String classification,
                                                int requiredClearance) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("type", "DOCUMENT");
        m.put("owner", owner);
        m.put("department", dept);
        m.put("classification", classification);
        m.put("requiredClearance", requiredClearance);
        return m;
    }

    @Test
    void denyOverridesPermitEvenWhenPermitHasHigherPriority() {
        List<Policy> policies = List.of(
                policy(1, "admin-all", Effect.PERMIT, "DOCUMENT", "*", "subject.title == 'admin'", 100),
                policy(2, "offhours-delete", Effect.DENY, "DOCUMENT", "DELETE", "env.hour >= 18", 10));

        PolicyEngine.Decision d = engine.evaluate(policies,
                subject("admin", "EXEC", 5, "CN", "admin"),
                document("alice", "ENG", "INTERNAL", 2),
                "DELETE", Map.of("hour", 22));

        assertFalse(d.permitted());
        assertEquals("DENY", d.effect());
        // 命中的是 DENY 那条，而不是优先级更高的 PERMIT
        assertEquals("offhours-delete", d.policyName());
    }

    @Test
    void higherPriorityPermitWinsAmongPermits() {
        List<Policy> policies = List.of(
                policy(1, "anyone-read-public", Effect.PERMIT, "DOCUMENT", "READ",
                        "resource.classification == 'PUBLIC'", 10),
                policy(2, "owner-full", Effect.PERMIT, "DOCUMENT", "*",
                        "resource.owner == subject.username", 50));

        PolicyEngine.Decision d = engine.evaluate(policies,
                subject("alice", "ENG", 3, "CN", "engineer"),
                document("alice", "ENG", "PUBLIC", 1),
                "READ", Map.of("hour", 10));

        assertTrue(d.permitted());
        assertEquals("owner-full", d.policyName());
    }

    @Test
    void noMatchingPolicyFallsBackToDefaultDeny() {
        List<Policy> policies = List.of(
                policy(1, "same-dept-internal", Effect.PERMIT, "DOCUMENT", "READ",
                        "resource.department == subject.department", 20));

        PolicyEngine.Decision d = engine.evaluate(policies,
                subject("bob", "SALES", 2, "CN", "sales"),
                document("alice", "ENG", "INTERNAL", 2),
                "READ", Map.of("hour", 10));

        assertFalse(d.permitted());
        assertTrue(d.reason().contains("default deny"));
        assertEquals(1, d.trace().size());
        assertFalse(d.trace().get(0).matched());
    }

    @Test
    void blankConditionMatchesUnconditionally() {
        List<Policy> policies = List.of(
                policy(1, "anyone-create", Effect.PERMIT, "DOCUMENT", "CREATE", null, 12));

        PolicyEngine.Decision d = engine.evaluate(policies,
                subject("bob", "SALES", 2, "CN", "sales"),
                Map.of("type", "DOCUMENT"),
                "CREATE", Map.of("hour", 10));

        assertTrue(d.permitted());
        assertEquals("anyone-create", d.policyName());
    }

    @Test
    void scopeFiltersByResourceTypeAndAction() {
        Policy userRead = policy(1, "user-read", Effect.PERMIT, "USER", "READ",
                "subject.title == 'admin'", 25);
        Policy docRead = policy(2, "doc-read", Effect.PERMIT, "DOCUMENT", "READ",
                "resource.classification == 'PUBLIC'", 30);

        // 资源类型是 DOCUMENT，USER 那条不该进入判定
        PolicyEngine.Decision d = engine.evaluate(List.of(userRead, docRead),
                subject("admin", "EXEC", 5, "CN", "admin"),
                document("alice", "ENG", "PUBLIC", 1),
                "READ", Map.of("hour", 10));
        assertTrue(d.permitted());
        assertEquals("doc-read", d.policyName());

        // 动作对不上：只有 READ 策略时请求 DELETE → 默认拒绝
        PolicyEngine.Decision d2 = engine.evaluate(List.of(docRead),
                subject("admin", "EXEC", 5, "CN", "admin"),
                document("alice", "ENG", "PUBLIC", 1),
                "DELETE", Map.of("hour", 10));
        assertFalse(d2.permitted());
    }

    @Test
    void wildcardActionAndResourceTypeApply() {
        Policy wildcard = policy(1, "admin-all", Effect.PERMIT, "*", "*",
                "subject.title == 'admin'", 5);

        PolicyEngine.Decision d = engine.evaluate(List.of(wildcard),
                subject("admin", "EXEC", 5, "CN", "admin"),
                Map.of("type", "POLICY"),
                "DELETE", Map.of("hour", 10));

        assertTrue(d.permitted());
    }

    @Test
    void brokenExpressionIsTreatedAsNotMatchedAndRecordedInTrace() {
        List<Policy> policies = List.of(
                policy(1, "broken", Effect.PERMIT, "DOCUMENT", "READ",
                        "resource.nope.callSomething()", 10));

        PolicyEngine.Decision d = engine.evaluate(policies,
                subject("alice", "ENG", 3, "CN", "engineer"),
                document("alice", "ENG", "INTERNAL", 2),
                "READ", Map.of("hour", 10));

        assertFalse(d.permitted());
        assertNotNull(d.trace().get(0).error());
    }

    @Test
    void dangerousExpressionsAreRejected() {
        // 类型引用：可达 JVM 的入口
        assertThrows(IllegalArgumentException.class,
                () -> engine.validate("T(java.lang.Runtime).getRuntime().exec('x')"));
        // 构造 + 类加载
        assertThrows(IllegalArgumentException.class,
                () -> engine.validate("new java.lang.ProcessBuilder('sh')"));
        assertThrows(IllegalArgumentException.class,
                () -> engine.validate("subject.getClass().getClassLoader()"));
        // 变量 / bean 引用
        assertThrows(IllegalArgumentException.class, () -> engine.validate("#root != null"));
    }

    @Test
    void legalExpressionsPassValidation() {
        engine.validate(null);
        engine.validate("   ");
        engine.validate("resource.classification == 'CONFIDENTIAL' && subject.region != 'CN'");
        engine.validate("subject.clearance < resource.requiredClearance");
        engine.validate("env.hour < 9 || env.hour >= 18");
    }

    @Test
    void environmentAttributesParticipateInDecision() {
        List<Policy> policies = List.of(
                policy(1, "owner-full", Effect.PERMIT, "DOCUMENT", "*",
                        "resource.owner == subject.username", 10),
                policy(2, "offhours-delete", Effect.DENY, "DOCUMENT", "DELETE",
                        "env.hour < 9 || env.hour >= 18", 100));

        Map<String, Object> subj = subject("alice", "ENG", 3, "CN", "engineer");
        Map<String, Object> doc = document("alice", "ENG", "INTERNAL", 2);

        assertTrue(engine.evaluate(policies, subj, doc, "DELETE", Map.of("hour", 14)).permitted());
        assertFalse(engine.evaluate(policies, subj, doc, "DELETE", Map.of("hour", 23)).permitted());
    }

    @Test
    void disabledPoliciesAreIgnored() {
        Policy disabled = policy(1, "owner-full", Effect.PERMIT, "DOCUMENT", "*",
                "resource.owner == subject.username", 10);
        disabled.setEnabled(false);

        PolicyEngine.Decision d = engine.evaluate(List.of(disabled),
                subject("alice", "ENG", 3, "CN", "engineer"),
                document("alice", "ENG", "INTERNAL", 2),
                "READ", Map.of("hour", 10));

        assertFalse(d.permitted());
        assertTrue(d.trace().isEmpty());
    }
}
