package com.example.abac.agent.service;

import com.example.abac.agent.model.AgentReviewTask;
import com.example.abac.agent.model.ToolCallRequest;
import com.example.abac.agent.model.ToolExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI Agent 前置校验门面：模拟 Agent 在调用工具前提交的校验请求。
 *
 * 与风控服务同一套 ABAC 地基，这里演示 Agent 场景特有的两件事：
 *  1. 工具级策略 —— 外链白名单（WEB/FETCH）、危险 syscall（CODE/EXECUTE）、
 *     群发/大额转 REVIEW、低信任 Agent 限制（EMAIL/SEND）。
 *  2. 会话状态累计 —— 同一会话的外联/发信/转账次数进 resource 属性包
 *     （sessionFetchCount / sessionSendCount 等），让"会话次数上限"策略成立。
 *
 * 状态全部在内存（key 带会话 id），演示级实现，重启即清零。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AbacClient abacClient;
    private final int maxPendingReviews;

    private final AtomicLong idSeq = new AtomicLong(1);
    /** 会话累计：key = sessionId → 各工具调用次数。 */
    private final Map<String, SessionStats> sessions = new ConcurrentHashMap<>();
    private final List<ToolExecution> executions = Collections.synchronizedList(new ArrayList<>());
    private final Map<Long, AgentReviewTask> reviews = new ConcurrentHashMap<>();

    public AgentService(AbacClient abacClient,
                        @Value("${agent.review-max-pending:200}") int maxPendingReviews) {
        this.abacClient = abacClient;
        this.maxPendingReviews = maxPendingReviews;
    }

    /** 校验结果：effect ∈ {PERMIT, DENY, REVIEW}；DENY 时 reason 说明被哪条策略拦截。 */
    public record ToolResult(String effect, boolean permitted, String reason,
                             String policyName, Long reviewId, ToolExecution execution) {
        public static ToolResult permit(ToolExecution execution) {
            return new ToolResult("PERMIT", true, null, execution.policyName(), null, execution);
        }

        public static ToolResult deny(String reason, String policyName) {
            return new ToolResult("DENY", false, reason, policyName, null, null);
        }

        public static ToolResult review(Long reviewId, String policyName) {
            return new ToolResult("REVIEW", false, null, policyName, reviewId, null);
        }
    }

    public ToolResult execute(ToolCallRequest req, Map<String, Object> subject, String sessionId) {
        if (req == null || req.toolType() == null || req.toolType().isBlank()
                || req.operation() == null || req.operation().isBlank()) {
            throw new IllegalArgumentException("toolType 与 operation 必填（如 WEB/FETCH）");
        }
        String toolType = req.toolType().trim().toUpperCase();
        String operation = req.operation().trim().toUpperCase();
        String username = String.valueOf(subject.getOrDefault("username", "anonymous"));
        SessionStats stats = session(sessionId);
        Map<String, Object> params = new LinkedHashMap<>();
        if (req.params() != null) {
            params.putAll(req.params());
        }
        // 会话状态注入资源属性：让"会话次数上限"类策略成立
        params.put("sessionFetchCount", stats.fetchCount);
        params.put("sessionSendCount", stats.sendCount);
        params.put("sessionTransferCount", stats.transferCount);
        params.put("sessionExecuteCount", stats.executeCount);
        params.put("sessionDeleteCount", stats.deleteCount);
        params.put("urlDomain", deriveUrlDomain(params.get("url")));

        Map<String, Object> resource = Map.of("type", toolType, "attributes", params);
        AbacClient.Decision d = abacClient.decide(subject, resource, operation);
        String summary = summarize(toolType, operation, params);

        return switch (d.effect()) {
            case "PERMIT" -> {
                stats.record(toolType, operation);
                ToolExecution execution = new ToolExecution(idSeq.getAndIncrement(), username,
                        String.valueOf(subject.getOrDefault("agentId", "unknown")),
                        toolType, operation, summary, "PERMIT", d.policyName(), System.currentTimeMillis());
                executions.add(execution);
                log.info("[agent] PERMIT {} {} {} ({})", subject.get("agentId"), toolType, operation, d.policyName());
                yield ToolResult.permit(execution);
            }
            case "REVIEW" -> {
                long pending = reviews.values().stream()
                        .filter(t -> t.status() == AgentReviewTask.Status.PENDING).count();
                if (pending >= maxPendingReviews) {
                    yield ToolResult.deny("复核队列已满，请稍后重试", d.policyName());
                }
                long id = idSeq.getAndIncrement();
                AgentReviewTask task = new AgentReviewTask(id, username,
                        String.valueOf(subject.getOrDefault("agentId", "unknown")),
                        toolType, operation, summary, d.policyName(), System.currentTimeMillis());
                reviews.put(id, task);
                log.info("[agent] REVIEW {} {} {} -> task#{} ({})", subject.get("agentId"), toolType, operation, id, d.policyName());
                yield ToolResult.review(id, d.policyName());
            }
            default -> {
                log.info("[agent] DENY {} {} {} ({}: {})", subject.get("agentId"), toolType, operation, d.policyName(), d.reason());
                yield ToolResult.deny(d.reason() == null ? "denied by policy" : d.reason(), d.policyName());
            }
        };
    }

    /** 人工批准复核：批准后才执行并计入会话统计（与风控同语义）。 */
    public ToolResult approveReview(long id, String actor) {
        AgentReviewTask t = requirePending(id);
        t.decide(AgentReviewTask.Status.APPROVED, actor);
        ToolExecution execution = new ToolExecution(idSeq.getAndIncrement(), t.username(),
                t.agentId(), t.toolType(), t.operation(), t.summary(), "REVIEW_APPROVED",
                t.policyName(), System.currentTimeMillis());
        executions.add(execution);
        log.info("[agent] review#{} APPROVED by {} ({} {})", id, actor, t.toolType(), t.operation());
        return ToolResult.permit(execution);
    }

    public void rejectReview(long id, String actor) {
        AgentReviewTask t = requirePending(id);
        t.decide(AgentReviewTask.Status.REJECTED, actor);
        log.info("[agent] review#{} REJECTED by {}", id, actor);
    }

    public List<AgentReviewTask> listReviews() {
        List<AgentReviewTask> all = new ArrayList<>(reviews.values());
        all.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        return all;
    }

    public List<ToolExecution> myExecutions(String username) {
        return executions.stream().filter(e -> e.username().equals(username)).toList();
    }

    /** 会话统计：次数类字段给前端展示。 */
    public Map<String, Object> sessionStats(String sessionId) {
        SessionStats s = session(sessionId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("fetchCount", s.fetchCount);
        m.put("sendCount", s.sendCount);
        m.put("transferCount", s.transferCount);
        m.put("executeCount", s.executeCount);
        m.put("deleteCount", s.deleteCount);
        m.put("limitFetch", 10);
        m.put("limitSend", 20);
        return m;
    }

    private AgentReviewTask requirePending(long id) {
        AgentReviewTask t = reviews.get(id);
        if (t == null) {
            throw new IllegalArgumentException("复核任务不存在: " + id);
        }
        if (t.status() != AgentReviewTask.Status.PENDING) {
            throw new IllegalArgumentException("复核任务已处理: " + id + " (" + t.status() + ")");
        }
        return t;
    }

    private SessionStats session(String sessionId) {
        String key = sessionId == null || sessionId.isBlank() ? "default" : sessionId.trim();
        return sessions.computeIfAbsent(key, k -> new SessionStats());
    }

    /** 从 URL 提取域名（去协议/端口/路径），供外链白名单策略使用。 */
    private static String deriveUrlDomain(Object url) {
        if (url == null) {
            return "UNKNOWN";
        }
        String s = String.valueOf(url).trim().toLowerCase();
        if (s.startsWith("http://")) {
            s = s.substring(7);
        } else if (s.startsWith("https://")) {
            s = s.substring(8);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(0, colon);
        }
        return s.isBlank() ? "UNKNOWN" : s;
    }

    /** 给前端展示的一行摘要：关键参数（金额/收件人/路径/域名）。 */
    private static String summarize(String toolType, String operation, Map<String, Object> params) {
        return switch (toolType) {
            case "WEB" -> "url=" + params.getOrDefault("url", "-");
            case "EMAIL" -> "recipients=" + params.getOrDefault("recipientCount", "-");
            case "PAYMENT" -> "amount=" + params.getOrDefault("amount", "-");
            case "FS" -> "path=" + params.getOrDefault("path", "-");
            case "CODE" -> "len=" + String.valueOf(params.getOrDefault("code", "")).length();
            default -> operation;
        };
    }

    private static final class SessionStats {
        private int fetchCount;
        private int sendCount;
        private int transferCount;
        private int executeCount;
        private int deleteCount;

        synchronized void record(String toolType, String operation) {
            switch (toolType) {
                case "WEB" -> fetchCount++;
                case "EMAIL" -> sendCount++;
                case "PAYMENT" -> transferCount++;
                case "FS" -> deleteCount++;
                case "CODE" -> executeCount++;
                default -> { }
            }
        }
    }
}
