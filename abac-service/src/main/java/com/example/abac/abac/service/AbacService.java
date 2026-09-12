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
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** 决策结果缓存上限：超过即整体清空（与 PolicyEngine 表达式缓存同样的简易容量保护）。 */
    private static final int MAX_DECISION_CACHE = 1024;

    /** 批量裁决单次请求数上限：防止单个请求一次问数千条把网关↔PDP 的请求体撑爆。 */
    private static final int MAX_BATCH_SIZE = 500;

    private final PolicyRepository policyRepository;
    private final PolicyEngine policyEngine;
    private final PipClient pipClient;
    private final boolean pipFailClosed;

    /**
     * 决策结果缓存：key = (subject + resource + action + 有效环境) 的规范化 JSON。
     * 命中即跳过 policyRepository.findAll() 与 evaluate 逐策略循环，是列表接口行级过滤
     * （每个候选文档一次裁决）最主要的重复开销来源，缓存后显著降压。
     * 仅在资源属性完整解析（PIP 正常）时写入；PIP 失败走最小属性或 fail-closed 时不缓存，
     * 避免把"属性不全时的裁决"误当成正确结果复用。策略增删改会清空整张缓存
     * （任一策略都可能改变任一裁决），保证不出现陈旧决策。
     */
    private final Map<String, PolicyEngine.Decision> decisionCache = new ConcurrentHashMap<>();
    private static final ObjectMapper KEY_MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();

    /**
     * 策略全表缓存：策略集合极少变动，裁决时直接复用，避免每次 decide 都 findAll() 打库
     * （网关每请求一次 PDP、业务服务每次对象级校验都是一次）。CRUD / 播种后整体失效。
     * {@code policyListVersion} 随每次重新加载递增并参与决策缓存 key：
     * 提交后清空缓存的窗口内，任何基于旧快照写入的裁决都带着旧版本号，永远无法命中新缓存。
     */
    private volatile List<Policy> policyListCache;
    private volatile long policyListVersion;

    public AbacService(PolicyRepository policyRepository,
                       PolicyEngine policyEngine,
                       PipClient pipClient,
                       @Value("${app.pip-fail-closed:true}") boolean pipFailClosed) {
        this.policyRepository = policyRepository;
        this.policyEngine = policyEngine;
        this.pipClient = pipClient;
        this.pipFailClosed = pipFailClosed;
    }

    /** 策略快照 + 加载版本：版本参与决策缓存 key，保证陈旧快照的裁决不可命中。 */
    private record LoadedPolicies(List<Policy> policies, long version) {
    }

    /** 取策略全表（带内存缓存，失效后重载），并返回当前快照版本。 */
    private LoadedPolicies loadPolicies() {
        List<Policy> cached = policyListCache;
        if (cached != null) {
            return new LoadedPolicies(cached, policyListVersion);
        }
        synchronized (this) {
            if (policyListCache != null) {
                return new LoadedPolicies(policyListCache, policyListVersion);
            }
            List<Policy> fresh = policyRepository.findAll();
            policyListCache = fresh;
            policyListVersion++;
            return new LoadedPolicies(fresh, policyListVersion);
        }
    }

    /**
     * 策略集合变更后失效缓存。事务内调用时注册到<b>提交后</b>执行：
     * 若在提交前清空，其他线程在事务未提交时 findAll 仍会读到旧策略，
     * 把旧快照写进策略列表缓存和决策缓存——提交后这两个缓存都不会再失效。
     * 无事务（如单条 save 的 createPolicy）则立即失效。
     */
    private void invalidatePolicies() {
        Runnable invalidate = () -> {
            policyListCache = null;
            decisionCache.clear();
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate.run();
                }
            });
        } else {
            invalidate.run();
        }
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
        Policy saved = policyRepository.save(p);
        invalidatePolicies();
        return toDto(saved);
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
        invalidatePolicies(); // 策略变更后任一裁决都可能变，整表失效
        return toDto(policyRepository.save(p));
    }

    @Transactional
    public void deletePolicy(Long id) {
        if (!policyRepository.existsById(id)) {
            throw new NotFoundException("policy not found: " + id);
        }
        policyRepository.deleteById(id);
        invalidatePolicies();
    }

    // ---------------- 裁决 ----------------

    public PolicyEngine.Decision decide(DecisionRequest req) {
        LoadedPolicies lp = loadPolicies();
        return decideCached(lp.policies(), lp.version(), req);
    }

    /** 批量裁决：业务服务按行过滤时一次问多个资源，避免 N 次 HTTP 往返。 */
    public List<PolicyEngine.Decision> decideBatch(List<DecisionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        if (requests.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batch size exceeds limit: " + MAX_BATCH_SIZE);
        }
        LoadedPolicies lp = loadPolicies();
        List<PolicyEngine.Decision> out = new ArrayList<>(requests.size());
        for (DecisionRequest req : requests) {
            out.add(decideCached(lp.policies(), lp.version(), req));
        }
        return out;
    }

    /**
     * 带缓存的单次裁决。策略集合与版本由调用方传入（decide 与 decideBatch 各自取一次，
     * 避免批量内重复 findAll；版本保证旧快照的裁决不落新缓存）。
     * 仅当资源属性经 PIP 完整解析（resolved=true）时查/写缓存；PIP 失败或走最小属性路径
     * 一律实时判定且不缓存，防止"属性不全时的裁决"被误当正确结果复用。
     */
    private PolicyEngine.Decision decideCached(List<Policy> policies, long version, DecisionRequest req) {
        Map<String, Object> resource;
        boolean resolved;
        try {
            resource = resolveResource(req == null ? null : req.resource());
            resolved = true;
        } catch (PipClient.PipException e) {
            if (pipFailClosed) {
                log.warn("PIP 不可用，fail-closed 拒绝: {}", e.getMessage());
                return PolicyEngine.Decision.deny(
                        "PIP unavailable, fail-closed: " + e.getMessage(), List.of());
            }
            resource = minimalResource(req == null ? null : req.resource());
            resolved = false;
        }
        if (req == null) {
            return PolicyEngine.Decision.deny("empty decision request", List.of());
        }
        if (resolved) {
            Map<String, Object> env = PolicyEngine.resolveEnvironment(req.environment());
            String key = cacheKey(version, req.subject(), resource, req.action(), env);
            if (key != null) {
                PolicyEngine.Decision cached = decisionCache.get(key);
                if (cached != null) {
                    return cached;
                }
                PolicyEngine.Decision d = policyEngine.evaluate(policies, req.subject(),
                        resource, req.action(), req.environment());
                if (decisionCache.size() >= MAX_DECISION_CACHE) {
                    decisionCache.clear();
                }
                decisionCache.put(key, d);
                return d;
            }
        }
        return policyEngine.evaluate(policies, req.subject(), resource, req.action(),
                req.environment());
    }

    /** 规范化缓存 key：版本 + subject/resource/action/有效环境，键名排序保证稳定。 */
    private String cacheKey(long version, Map<String, Object> subject, Map<String, Object> resource,
                            String action, Map<String, Object> env) {
        try {
            Map<String, Object> key = new LinkedHashMap<>();
            key.put("v", version);
            key.put("s", subject);
            key.put("r", resource);
            key.put("a", action == null ? "" : action);
            key.put("e", env);
            return KEY_MAPPER.writeValueAsString(key);
        } catch (Exception e) {
            log.debug("决策缓存 key 序列化失败，跳过缓存: {}", e.getMessage());
            return null;
        }
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
        save("DOC-100 非工作时间禁止删除文档",
                "18:00-09:00 之外不允许删除任何文档（环境属性约束，连管理员也不例外）",
                Effect.DENY, "DOCUMENT", "DELETE", "env.hour < 9 || env.hour >= 18", 100);

        save("DOC-95 境外主体禁止读取机密文档",
                "数据属地：region != CN 的主体读不到 CONFIDENTIAL 文档",
                Effect.DENY, "DOCUMENT", "READ",
                "resource.classification == 'CONFIDENTIAL' && subject.region != 'CN'", 95);

        save("DOC-90 密级不足禁止读取",
                "subject.clearance < resource.requiredClearance 即拒绝（PUBLIC=1 / INTERNAL=2 / CONFIDENTIAL=4 / SECRET=5）",
                Effect.DENY, "DOCUMENT", "READ",
                "subject.clearance < resource.requiredClearance", 90);

        save("DOC-30 公开文档人人可读", "classification=PUBLIC 的文档不设门槛",
                Effect.PERMIT, "DOCUMENT", "READ", "resource.classification == 'PUBLIC'", 30);

        // 只约束部门，不约束密级：密级由 DOC-90（DENY）把关，这里放行不等于放行全部——
        // 密级不够的人会被 DOC-90 拦掉，地区不符的会被 DOC-95 拦掉。PERMIT 只回答"部门对不对"。
        save("DOC-20 同部门可读本部门文档",
                "本部门文档对本部门可见；跨部门一律拒绝（密级/地区另由 DENY 策略约束）",
                Effect.PERMIT, "DOCUMENT", "READ",
                "resource.department == subject.department", 20);

        // 集合动作 LIST：列表接口无具体资源，网关只能问"能不能列这个域"。
        // 放行列表不等于放行每一行——真正的行级边界在业务服务（见 ADR 0005）。
        save("DOC-18 登录用户可列出文档", "列表接口放行给所有登录用户，可见行由业务服务按行级策略过滤",
                Effect.PERMIT, "DOCUMENT", "LIST", null, 18);

        save("USR-24 管理员/经理可列出用户", "用户列表对 manager / admin 可见",
                Effect.PERMIT, "USER", "LIST",
                "subject.title == 'admin' || subject.title == 'manager'", 24);

        save("AUD-38 仅管理员可列出审计", "审计列表只对 admin 开放",
                Effect.PERMIT, "AUDIT", "LIST", "subject.title == 'admin'", 38);

        save("POL-07 经理可列出策略", "manager 能列策略但不能改（配合 POL-35 形成读写分离）",
                Effect.PERMIT, "POLICY", "LIST",
                "subject.title == 'manager' || subject.title == 'admin'", 7);

        save("DOC-15 经理及以上可发布文档", "PUBLISH 动作收敛到 manager / admin",
                Effect.PERMIT, "DOCUMENT", "PUBLISH",
                "subject.title == 'manager' || subject.title == 'admin'", 15);

        save("DOC-12 登录用户可创建文档", "创建不设额外门槛（无条件命中的 PERMIT 示例）",
                Effect.PERMIT, "DOCUMENT", "CREATE", null, 12);

        save("DOC-10 作者可操作自己的文档", "owner 对本人的文档有全部操作权（action 通配 *）",
                Effect.PERMIT, "DOCUMENT", "*", "resource.owner == subject.username", 10);

        save("DOC-05 管理员可操作全部文档", "admin 岗位对文档域全权（受 DENY 策略约束，不翻案）",
                Effect.PERMIT, "DOCUMENT", "*", "subject.title == 'admin'", 5);

        save("USR-60 仅管理员可修改用户属性", "改主体属性等于改权限，只放开给 admin",
                Effect.PERMIT, "USER", "UPDATE", "subject.title == 'admin'", 60);

        save("USR-25 管理员/经理可查看用户属性", "用户属性页对 manager / admin 可见",
                Effect.PERMIT, "USER", "READ",
                "subject.title == 'admin' || subject.title == 'manager'", 25);

        save("AUD-40 仅管理员可查看审计日志", "审计域只对 admin 开放",
                Effect.PERMIT, "AUDIT", "READ", "subject.title == 'admin'", 40);

        save("POL-35 管理员可管理策略", "策略域写操作只对 admin 开放",
                Effect.PERMIT, "POLICY", "*", "subject.title == 'admin'", 35);

        save("POL-08 经理可查看策略", "manager 能看策略但不能改（配合 POL-35 形成读写分离）",
                Effect.PERMIT, "POLICY", "READ",
                "subject.title == 'manager' || subject.title == 'admin'", 8);

        // ---- 风控域（TRADE）：独立 risk-service 的下单前置校验，演示 REVIEW 第三态与状态累计 ----
        save("TRD-85 单笔大额转人工复核", "金额超过 50000 的交易不直接放行，转入工复核（REVIEW 第三态）",
                Effect.REVIEW, "TRADE", "EXECUTE", "resource.amount > 50000", 85);

        save("TRD-80 当日累计超限拒绝", "当日累计成交金额（含本笔）超过 100000 直接拒绝",
                Effect.DENY, "TRADE", "EXECUTE", "resource.cumulativeAfter > 100000", 80);

        save("TRD-75 境外网页渠道禁止", "境外 + 网页渠道的组合直接拒绝（典型渠道风控）",
                Effect.DENY, "TRADE", "EXECUTE",
                "resource.channel == 'WEB' && resource.region != 'CN'", 75);

        save("TRD-70 工程师单笔限额", "engineer 岗位单笔超过 20000 拒绝（演示用 alice）",
                Effect.DENY, "TRADE", "EXECUTE",
                "subject.title == 'engineer' && resource.amount > 20000", 70);

        save("TRD-18 登录用户可查看交易", "交易列表对所有登录用户可见，单笔裁决由上面的规则把关",
                Effect.PERMIT, "TRADE", "LIST", null, 18);

        save("TRD-12 交易兜底放行", "无风控规则命中时允许成交（DENY/REVIEW 规则在上层拦截）",
                Effect.PERMIT, "TRADE", "EXECUTE", null, 12);

        // ---- AI Agent 前置校验域（TOOL）：agent-service 在工具调用前问 PDP ----
        // 资源类型是工具域（WEB/EMAIL/PAYMENT/FS/CODE），动作是工具操作（FETCH/SEND/...）。
        save("EML-99 群发邮件转人工复核", "单封邮件收件人超过 5 人转人工复核（防 Agent 批量骚扰）",
                Effect.REVIEW, "EMAIL", "SEND", "resource.recipientCount > 5", 99);

        save("WEB-95 外链域名白名单", "目标 URL 域名不在白名单内直接拒绝（防 SSRF/恶意外联）",
                Effect.DENY, "WEB", "FETCH",
                "resource.urlDomain != 'example.com' && resource.urlDomain != 'api.example.com' && resource.urlDomain != 'cnb.example.com'", 95);

        save("WEB-92 会话外联次数上限", "同一会话累计外联超过 10 次拒绝（会话级状态累计）",
                Effect.DENY, "WEB", "FETCH", "resource.sessionFetchCount > 10", 92);

        save("PAY-90 大额转账转人工复核", "单笔转账超过 10000 转人工复核",
                Effect.REVIEW, "PAYMENT", "TRANSFER", "resource.amount > 10000", 90);

        save("FS-85 工作区外禁止删除", "删除路径不在 /workspace 内直接拒绝",
                Effect.DENY, "FS", "DELETE", "!resource.path.startsWith('/workspace')", 85);

        save("COD-80 危险命令禁止", "代码里出现 rm -rf 等危险删除命令直接拒绝（沙箱禁方法调用，用 matches 正则操作符检测）",
                Effect.DENY, "CODE", "EXECUTE", "resource.code matches '.*rm -rf.*'", 80);

        save("EML-78 会话发信次数上限", "同一会话累计发信超过 20 封拒绝",
                Effect.DENY, "EMAIL", "SEND", "resource.sessionSendCount > 20", 78);

        save("COD-75 疑似高危代码转复核", "代码里出现 ProcessBuild（进程启动类调用）转人工复核",
                Effect.REVIEW, "CODE", "EXECUTE", "resource.code matches '.*ProcessBuild.*'", 75);

        save("EML-70 低信任 Agent 群发限制", "低信任 Agent 单封超过 3 个收件人直接拒绝",
                Effect.DENY, "EMAIL", "SEND",
                "subject.trust == 'low' && resource.recipientCount > 3", 70);

        save("TOL-18 登录用户可查看工具调用", "工具调用记录对所有登录用户可见",
                Effect.PERMIT, "TOOL", "LIST", null, 18);

        save("TOL-15 工具调用网关兜底", "网关预裁兜底：细粒度工具校验由 agent-service 按工具域再问 PDP",
                Effect.PERMIT, "TOOL", "EXECUTE", null, 15);

        save("WEB-12 外联兜底放行", "白名单内的外联允许（白名单外已被 WEB-95 拦截）",
                Effect.PERMIT, "WEB", "FETCH", null, 12);

        save("EML-08 发信兜底放行", "常规发信允许（群发/低信任已被上层拦截）",
                Effect.PERMIT, "EMAIL", "SEND", null, 8);

        save("PAY-06 转账兜底放行", "常规转账允许（超限已被 PAY-90 拦截）",
                Effect.PERMIT, "PAYMENT", "TRANSFER", null, 6);

        save("FS-05 删除兜底放行", "工作区内删除允许（工作区外已被 FS-85 拦截）",
                Effect.PERMIT, "FS", "DELETE", null, 5);

        save("COD-04 代码执行兜底放行", "常规代码执行允许（危险调用已被 COD-80/COD-75 拦截）",
                Effect.PERMIT, "CODE", "EXECUTE", null, 4);

        log.info("[abac] seeded {} demo policies (deny-override, default deny)", policyRepository.count());
        invalidatePolicies();
    }

    private static final String SEED_MARKER = "DOC-100 非工作时间禁止删除文档";

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
            throw new IllegalArgumentException("effect 必填（PERMIT / DENY / REVIEW）");
        }
        try {
            return Effect.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("effect 只能是 PERMIT / DENY / REVIEW: " + raw);
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
