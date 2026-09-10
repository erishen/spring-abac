package com.example.abac.abac.engine;

import com.example.abac.abac.model.Effect;
import com.example.abac.abac.model.Policy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * ABAC 策略引擎（PDP 的核心）：把「主体属性 + 资源属性 + 动作 + 环境属性」喂给
 * 一组策略的 SpEL 条件表达式，按 <b>deny-override（拒绝优先）</b> 合并出最终裁决。
 *
 * 合并算法：
 *   1. 先筛出 enabled 且作用域匹配（resourceType / action，支持 "*" 通配）的策略；
 *   2. 按 priority 降序求值条件（数值越大越先判定）；
 *   3. 只要有任意 DENY 命中 → 最终 DENY（permit 再多也不翻案）；
 *   4. 否则只要有 PERMIT 命中 → 最终 PERMIT；
 *   5. 都没有命中 → 默认 DENY（default deny，安全默认）。
 *
 * 表达式求值结果非布尔、或求值抛异常时，一律按「未命中」处理并记录到 trace，
 * 这样一条写坏的策略只影响它自己，不会让整个 PDP 崩掉。
 */
@Component
public class PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);

    private static final int MAX_EXPRESSION_LENGTH = 1000;
    private static final int MAX_CACHE_SIZE = 512;

    /**
     * 表达式黑名单（正则）。策略表达式由管理员录入（可信输入），但仍做纵深防御：
     * 禁止类型引用 / 构造 / 类加载 / 反射出口，配合下面 setTypeLocator 抛异常，
     * 双保险确保表达式只能读属性、做比较，摸不到 JVM。
     *
     * 必须用正则而不是子串匹配：子串 "T(" 或 ".class" 会误伤正常属性名
     * （如 resource.classification 里就含 ".class"），这里都加了边界约束。
     */
    private static final List<Pattern> FORBIDDEN_PATTERNS = List.of(
            Pattern.compile("T\\s*\\("),          // 类型引用 T(java.lang.Runtime)
            Pattern.compile("\\bnew\\s+"),        // 构造
            Pattern.compile("getClass"),          // 反射入口
            Pattern.compile("\\.class\\b"),       // 类字面量（不匹配 .classification）
            Pattern.compile("[cC]lassLoader"),
            Pattern.compile("\\bRuntime\\b"),
            Pattern.compile("\\bProcessBuilder\\b"),
            Pattern.compile("\\bSystem\\s*\\."),
            Pattern.compile("forName"),
            Pattern.compile("exec\\s*\\("),
            Pattern.compile("#"),                 // SpEL 变量引用
            Pattern.compile("@")                  // bean 引用
    );

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ConcurrentHashMap<String, Expression> expressionCache = new ConcurrentHashMap<>();

    /** 裁决结果。effect = PERMIT / DENY；trace 记录每条策略的求值过程，便于解释"为什么"。 */
    public record Decision(String effect, boolean permitted, Long policyId, String policyName,
                           String reason, List<TraceEntry> trace) {

        public static Decision deny(String reason, List<TraceEntry> trace) {
            return new Decision(Effect.DENY.name(), false, null, null, reason, trace);
        }
    }

    /** 单条策略的求值痕迹（前端用它渲染"判定过程"）。 */
    public record TraceEntry(Long policyId, String policyName, String effect,
                             boolean matched, String error) {
    }

    public Decision evaluate(List<Policy> policies,
                             Map<String, Object> subject,
                             Map<String, Object> resource,
                             String action,
                             Map<String, Object> environment) {
        Map<String, Object> subj = subject == null ? Map.of() : subject;
        Map<String, Object> res = resource == null ? Map.of() : resource;
        Map<String, Object> env = environment == null || environment.isEmpty()
                ? defaultEnvironment() : environment;
        String act = action == null ? "" : action.trim().toUpperCase();
        String resourceType = res.get("type") == null ? "" : String.valueOf(res.get("type"));

        EvaluationContext ctx = buildContext(subj, res, env, act);

        List<Policy> candidates = (policies == null ? List.<Policy>of() : policies).stream()
                .filter(p -> p != null && p.isEnabled())
                .filter(p -> scopeMatches(p.getResourceType(), resourceType))
                .filter(p -> scopeMatches(p.getAction(), act))
                .sorted(Comparator.comparingInt(Policy::getPriority).reversed())
                .toList();

        List<TraceEntry> trace = new ArrayList<>();
        Policy denyHit = null;
        Policy permitHit = null;

        for (Policy p : candidates) {
            boolean matched;
            String error = null;
            try {
                matched = conditionMatches(p, ctx);
            } catch (Exception e) {
                matched = false;
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("policy condition evaluation failed: id={} name={} -> {}",
                        p.getId(), p.getName(), error);
            }
            trace.add(new TraceEntry(p.getId(), p.getName(),
                    p.getEffect() == null ? null : p.getEffect().name(), matched, error));
            if (!matched) {
                continue;
            }
            if (p.getEffect() == Effect.DENY) {
                if (denyHit == null) {
                    denyHit = p;
                }
            } else if (permitHit == null) {
                permitHit = p;
            }
        }

        if (denyHit != null) {
            return new Decision(Effect.DENY.name(), false, denyHit.getId(), denyHit.getName(),
                    "denied by policy: " + denyHit.getName(), List.copyOf(trace));
        }
        if (permitHit != null) {
            return new Decision(Effect.PERMIT.name(), true, permitHit.getId(), permitHit.getName(),
                    "permitted by policy: " + permitHit.getName(), List.copyOf(trace));
        }
        return Decision.deny("no matching policy (default deny)", List.copyOf(trace));
    }

    /**
     * 表达式语法校验：能解析 + 不在黑名单内即认为合法。
     * 供策略录入时提前拦截，避免脏表达式落库后每次判定都抛异常。
     */
    public void validate(String condition) {
        if (condition == null || condition.isBlank()) {
            return; // 空条件 = 无条件命中
        }
        if (condition.length() > MAX_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException(
                    "条件表达式过长（>" + MAX_EXPRESSION_LENGTH + " 字符）");
        }
        for (Pattern pattern : FORBIDDEN_PATTERNS) {
            if (pattern.matcher(condition).find()) {
                throw new IllegalArgumentException("条件表达式包含不允许的片段: " + pattern.pattern());
            }
        }
        parser.parseExpression(condition); // 语法错误抛 SpelParseException
    }

    private boolean conditionMatches(Policy p, EvaluationContext ctx) {
        String condition = p.getCondition();
        if (condition == null || condition.isBlank()) {
            return true;
        }
        Object value = parse(condition).getValue(ctx);
        return Boolean.TRUE.equals(value);
    }

    private Expression parse(String condition) {
        if (expressionCache.size() > MAX_CACHE_SIZE) {
            expressionCache.clear(); // 简易容量保护，避免策略被反复改写导致无限增长
        }
        return expressionCache.computeIfAbsent(condition, c -> {
            validate(c);
            return parser.parseExpression(c);
        });
    }

    /** 作用域匹配：策略写 "*" 或等于目标值（忽略大小写）即匹配；策略留空按 "*" 处理。 */
    private static boolean scopeMatches(String policyScope, String actual) {
        if (policyScope == null || policyScope.isBlank() || "*".equals(policyScope)) {
            return true;
        }
        return policyScope.equalsIgnoreCase(actual);
    }

    private EvaluationContext buildContext(Map<String, Object> subject,
                                           Map<String, Object> resource,
                                           Map<String, Object> env,
                                           String action) {
        Map<String, Object> root = new HashMap<>();
        root.put("subject", subject);
        root.put("resource", resource);
        root.put("env", env);
        root.put("environment", env); // 别名，写哪个都行
        root.put("action", action);

        StandardEvaluationContext ctx = new StandardEvaluationContext(root);
        // 属性访问只走 MapPropertyAccessor：表达式能读到的只有这三个属性包里的键。
        ctx.addPropertyAccessor(new MapPropertyAccessor());
        // 硬性封死类型引用（T(...) 与 new Xxx() 都经此解析），黑名单之外的最后一道闸。
        ctx.setTypeLocator(typeName -> {
            throw new SpelEvaluationException(SpelMessage.TYPE_NOT_FOUND, typeName);
        });
        return ctx;
    }

    /** 缺省环境属性：调用方未显式提供时，按服务本地时间补齐。 */
    private Map<String, Object> defaultEnvironment() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> env = new HashMap<>();
        env.put("hour", now.getHour());
        env.put("dayOfWeek", now.getDayOfWeek().name());
        env.put("ip", "127.0.0.1");
        return env;
    }
}
