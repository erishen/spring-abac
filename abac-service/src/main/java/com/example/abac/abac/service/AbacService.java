package com.example.abac.abac.service;

import com.example.abac.abac.dto.AbacDtos.CreatePolicyRequest;
import com.example.abac.abac.dto.AbacDtos.DecisionRequest;
import com.example.abac.abac.dto.AbacDtos.PolicyDto;
import com.example.abac.abac.dto.AbacDtos.ResourceRef;
import com.example.abac.abac.dto.AbacDtos.UpdatePolicyRequest;
import com.example.abac.abac.engine.PolicyEngine;
import com.example.abac.abac.exception.ConflictException;
import com.example.abac.abac.exception.NotFoundException;
import com.example.abac.abac.model.Effect;
import com.example.abac.abac.model.Policy;
import com.example.abac.abac.repository.PolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ABAC 服务门面：策略 CRUD + 裁决入口。
 *
 * 裁决时资源属性有两条来路：
 *   · 调用方已带全属性（业务服务做行级过滤时）→ 直接用；
 *   · 只给了资源 id（网关 PEP 从 URL 解析到的）→ 经 {@link PipClient} 回源补齐；
 * 回源失败时按 app.pip-fail-closed 决定 fail-closed（默认拒绝）还是继续判定。
 */
@Service
public class AbacService {

    private static final Logger log = LoggerFactory.getLogger(AbacService.class);

    private final PolicyRepository policyRepository;
    private final PolicyEngine policyEngine;
    private final PipClient pipClient;
    private final boolean pipFailClosed;

    public AbacService(PolicyRepository policyRepository,
                       PolicyEngine policyEngine,
                       PipClient pipClient,
                       @Value("${app.pip-fail-closed:true}") boolean pipFailClosed) {
        this.policyRepository = policyRepository;
        this.policyEngine = policyEngine;
        this.pipClient = pipClient;
        this.pipFailClosed = pipFailClosed;
    }

    // ---------------- 策略管理 ----------------

    public List<PolicyDto> listPolicies() {
        return policyRepository.findAll().stream()
                .sorted((a, b) -> Integer.compare(b.getPriority(), a.getPriority()))
                .map(AbacService::toDto)
                .toList();
    }

    public PolicyDto getPolicy(Long id) {
        return toDto(require(id));
    }

    public PolicyDto createPolicy(CreatePolicyRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("策略名不能为空");
        }
        if (policyRepository.findByName(req.name()).isPresent()) {
            throw new ConflictException("策略已存在: " + req.name());
        }
        Effect effect = parseEffect(req.effect());
        policyEngine.validate(req.condition()); // 提前拦截脏表达式，避免落库后每次判定都炸

        Policy p = new Policy();
        p.setName(req.name().trim());
        p.setDescription(req.description());
        p.setEffect(effect);
        p.setResourceType(blankToWildcard(req.resourceType()));
        p.setAction(blankToWildcard(req.action()));
        p.setCondition(req.condition());
        p.setPriority(req.priority() == null ? 0 : req.priority());
        p.setEnabled(req.enabled() == null || req.enabled());
        p.setCreatedAt(System.currentTimeMillis());
        return toDto(policyRepository.save(p));
    }

    @Transactional
    public PolicyDto updatePolicy(Long id, UpdatePolicyRequest req) {
        Policy p = require(id);
        if (req.name() != null && !req.name().isBlank() && !req.name().equals(p.getName())) {
            if (policyRepository.findByName(req.name()).isPresent()) {
                throw new ConflictException("策略已存在: " + req.name());
            }
            p.setName(req.name().trim());
        }
        if (req.description() != null) {
            p.setDescription(req.description());
        }
        if (req.effect() != null) {
            p.setEffect(parseEffect(req.effect()));
        }
        if (req.resourceType() != null) {
            p.setResourceType(blankToWildcard(req.resourceType()));
        }
        if (req.action() != null) {
            p.setAction(blankToWildcard(req.action()));
        }
        if (req.condition() != null) {
            policyEngine.validate(req.condition());
            p.setCondition(req.condition());
        }
        if (req.priority() != null) {
            p.setPriority(req.priority());
        }
        if (req.enabled() != null) {
            p.setEnabled(req.enabled());
        }
        return toDto(policyRepository.save(p));
    }

    @Transactional
    public void deletePolicy(Long id) {
        if (!policyRepository.existsById(id)) {
            throw new NotFoundException("policy not found: " + id);
        }
        policyRepository.deleteById(id);
    }

    // ---------------- 裁决 ----------------

    public PolicyEngine.Decision decide(DecisionRequest req) {
        Map<String, Object> resource;
        try {
            resource = resolveResource(req == null ? null : req.resource());
        } catch (PipClient.PipException e) {
            if (pipFailClosed) {
                log.warn("PIP 不可用，fail-closed 拒绝: {}", e.getMessage());
                return PolicyEngine.Decision.deny(
                        "PIP unavailable, fail-closed: " + e.getMessage(), List.of());
            }
            resource = minimalResource(req == null ? null : req.resource());
        }
        if (req == null) {
            return PolicyEngine.Decision.deny("empty decision request", List.of());
        }
        return policyEngine.evaluate(policyRepository.findAll(), req.subject(), resource,
                req.action(), req.environment());
    }

    /** 批量裁决：业务服务按行过滤时一次问多个资源，避免 N 次 HTTP 往返。 */
    public List<PolicyEngine.Decision> decideBatch(List<DecisionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<Policy> policies = policyRepository.findAll();
        List<PolicyEngine.Decision> out = new ArrayList<>(requests.size());
        for (DecisionRequest req : requests) {
            out.add(decideWithPolicies(policies, req));
        }
        return out;
    }

    private PolicyEngine.Decision decideWithPolicies(List<Policy> policies, DecisionRequest req) {
        Map<String, Object> resource;
        try {
            resource = resolveResource(req == null ? null : req.resource());
        } catch (PipClient.PipException e) {
            if (pipFailClosed) {
                return PolicyEngine.Decision.deny(
                        "PIP unavailable, fail-closed: " + e.getMessage(), List.of());
            }
            resource = minimalResource(req == null ? null : req.resource());
        }
        if (req == null) {
            return PolicyEngine.Decision.deny("empty decision request", List.of());
        }
        return policyEngine.evaluate(policies, req.subject(), resource, req.action(),
                req.environment());
    }

    /**
     * 组装资源属性包：调用方给的 attributes 为基准，
     * 缺失且带 id 时经 PIP 回源补齐（只补没有的键，调用方显式给的值优先）。
     */
    private Map<String, Object> resolveResource(ResourceRef ref) {
        if (ref == null) {
            return Map.of();
        }
        Map<String, Object> attrs = new HashMap<>();
        if (ref.attributes() != null) {
            attrs.putAll(ref.attributes());
        }
        if (ref.type() != null) {
            attrs.putIfAbsent("type", ref.type());
        }
        if (ref.id() != null) {
            attrs.putIfAbsent("id", ref.id());
        }
        boolean attributesMissing = ref.attributes() == null || ref.attributes().isEmpty();
        if (attributesMissing && ref.id() != null && !ref.id().isBlank()) {
            Map<String, Object> pipAttrs = pipClient.fetch(ref.type(), ref.id());
            pipAttrs.forEach(attrs::putIfAbsent);
        }
        return attrs;
    }

    /** PIP 失败且配置了 fail-open 时的最小属性包（只有 type/id）。 */
    private Map<String, Object> minimalResource(ResourceRef ref) {
        Map<String, Object> attrs = new HashMap<>();
        if (ref != null) {
            if (ref.type() != null) {
                attrs.put("type", ref.type());
            }
            if (ref.id() != null) {
                attrs.put("id", ref.id());
            }
        }
        return attrs;
    }

    // ---------------- 演示策略播种 ----------------

    /** 幂等播种：已存在同名策略则整体跳过。 */
    @Transactional
    public void seedIfEmpty() {
        if (policyRepository.findByName(SEED_MARKER).isPresent()) {
            return;
        }
        save("P-100 非工作时间禁止删除文档",
                "18:00-09:00 之外不允许删除任何文档（环境属性约束，连管理员也不例外）",
                Effect.DENY, "DOCUMENT", "DELETE", "env.hour < 9 || env.hour >= 18", 100);

        save("P-95 境外主体禁止读取机密文档",
                "数据属地：region != CN 的主体读不到 CONFIDENTIAL 文档",
                Effect.DENY, "DOCUMENT", "READ",
                "resource.classification == 'CONFIDENTIAL' && subject.region != 'CN'", 95);

        save("P-90 密级不足禁止读取",
                "subject.clearance < resource.requiredClearance 即拒绝（PUBLIC=1 / INTERNAL=2 / CONFIDENTIAL=4 / SECRET=5）",
                Effect.DENY, "DOCUMENT", "READ",
                "subject.clearance < resource.requiredClearance", 90);

        save("P-30 公开文档人人可读", "classification=PUBLIC 的文档不设门槛",
                Effect.PERMIT, "DOCUMENT", "READ", "resource.classification == 'PUBLIC'", 30);

        // 只约束部门，不约束密级：密级由 P-90（DENY）把关，这里放行不等于放行全部——
        // 密级不够的人会被 P-90 拦掉，地区不符的会被 P-95 拦掉。PERMIT 只回答"部门对不对"。
        save("P-20 同部门可读本部门文档",
                "本部门文档对本部门可见；跨部门一律拒绝（密级/地区另由 DENY 策略约束）",
                Effect.PERMIT, "DOCUMENT", "READ",
                "resource.department == subject.department", 20);

        // 集合动作 LIST：列表接口无具体资源，网关只能问"能不能列这个域"。
        // 放行列表不等于放行每一行——真正的行级边界在业务服务（见 ADR 0005）。
        save("P-18 登录用户可列出文档", "列表接口放行给所有登录用户，可见行由业务服务按行级策略过滤",
                Effect.PERMIT, "DOCUMENT", "LIST", null, 18);

        save("P-24 管理员/经理可列出用户", "用户列表对 manager / admin 可见",
                Effect.PERMIT, "USER", "LIST",
                "subject.title == 'admin' || subject.title == 'manager'", 24);

        save("P-38 仅管理员可列出审计", "审计列表只对 admin 开放",
                Effect.PERMIT, "AUDIT", "LIST", "subject.title == 'admin'", 38);

        save("P-07 经理可列出策略", "manager 能列策略但不能改（配合 P-35 形成读写分离）",
                Effect.PERMIT, "POLICY", "LIST",
                "subject.title == 'manager' || subject.title == 'admin'", 7);

        save("P-15 经理及以上可发布文档", "PUBLISH 动作收敛到 manager / admin",
                Effect.PERMIT, "DOCUMENT", "PUBLISH",
                "subject.title == 'manager' || subject.title == 'admin'", 15);

        save("P-12 登录用户可创建文档", "创建不设额外门槛（无条件命中的 PERMIT 示例）",
                Effect.PERMIT, "DOCUMENT", "CREATE", null, 12);

        save("P-10 作者可操作自己的文档", "owner 对本人的文档有全部操作权（action 通配 *）",
                Effect.PERMIT, "DOCUMENT", "*", "resource.owner == subject.username", 10);

        save("P-05 管理员可操作全部文档", "admin 岗位对文档域全权（受 DENY 策略约束，不翻案）",
                Effect.PERMIT, "DOCUMENT", "*", "subject.title == 'admin'", 5);

        save("P-60 仅管理员可修改用户属性", "改主体属性等于改权限，只放开给 admin",
                Effect.PERMIT, "USER", "UPDATE", "subject.title == 'admin'", 60);

        save("P-25 管理员/经理可查看用户属性", "用户属性页对 manager / admin 可见",
                Effect.PERMIT, "USER", "READ",
                "subject.title == 'admin' || subject.title == 'manager'", 25);

        save("P-40 仅管理员可查看审计日志", "审计域只对 admin 开放",
                Effect.PERMIT, "AUDIT", "READ", "subject.title == 'admin'", 40);

        save("P-35 管理员可管理策略", "策略域写操作只对 admin 开放",
                Effect.PERMIT, "POLICY", "*", "subject.title == 'admin'", 35);

        save("P-08 经理可查看策略", "manager 能看策略但不能改（配合 P-35 形成读写分离）",
                Effect.PERMIT, "POLICY", "READ",
                "subject.title == 'manager' || subject.title == 'admin'", 8);

        System.out.println("[abac] seeded " + policyRepository.count()
                + " demo policies (deny-override, default deny)");
    }

    private static final String SEED_MARKER = "P-100 非工作时间禁止删除文档";

    private void save(String name, String description, Effect effect, String resourceType,
                      String action, String condition, int priority) {
        Policy p = new Policy();
        p.setName(name);
        p.setDescription(description);
        p.setEffect(effect);
        p.setResourceType(resourceType);
        p.setAction(action);
        p.setCondition(condition);
        p.setPriority(priority);
        p.setEnabled(true);
        p.setCreatedAt(System.currentTimeMillis());
        policyRepository.save(p);
    }

    // ---------------- 工具 ----------------

    private Policy require(Long id) {
        return policyRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("policy not found: " + id));
    }

    private static Effect parseEffect(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("effect 必填（PERMIT / DENY）");
        }
        try {
            return Effect.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("effect 只能是 PERMIT 或 DENY: " + raw);
        }
    }

    private static String blankToWildcard(String v) {
        return (v == null || v.isBlank()) ? "*" : v.trim().toUpperCase();
    }

    private static PolicyDto toDto(Policy p) {
        return new PolicyDto(p.getId(), p.getName(), p.getDescription(),
                p.getEffect() == null ? null : p.getEffect().name(),
                p.getResourceType(), p.getAction(), p.getCondition(),
                p.getPriority(), p.isEnabled(), p.getCreatedAt());
    }
}
