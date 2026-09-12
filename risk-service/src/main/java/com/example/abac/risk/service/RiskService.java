package com.example.abac.risk.service;

import com.example.abac.risk.model.ReviewTask;
import com.example.abac.risk.model.TradeExecution;
import com.example.abac.risk.model.TradeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 风控门面：模拟交易的前置校验。
 *
 * 流程（与文档域不同，这里演示风控特有的两件事）：
 *  1. REVIEW 第三态 —— PDP 返回 REVIEW 时不拒绝也不放行，落成待办由人工批准/拒绝，
 *     批准才计入当日累计（即"超限单笔 → 人工复核 → 通过后才占额度"）。
 *  2. 状态累计 —— 当日累计金额/次数进 resource 属性包（cumulativeAmount / cumulativeAfter）
 *     再问 PDP，让"当日累计超限"这类有状态策略成立。
 *
 * 状态全部在内存（key 带日期，跨天自然重置），演示级实现，重启即清零。
 */
@Service
public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    private final AbacClient abacClient;
    private final int maxPendingReviews;

    private final AtomicLong idSeq = new AtomicLong(1);
    /** 当日累计：key = "yyyy-MM-dd:username" → 累计金额/次数。 */
    private final Map<String, Accumulator> accumulators = new ConcurrentHashMap<>();
    private final List<TradeExecution> trades = Collections.synchronizedList(new ArrayList<>());
    private final Map<Long, ReviewTask> reviews = new ConcurrentHashMap<>();

    public RiskService(AbacClient abacClient,
                       @Value("${risk.review-max-pending:200}") int maxPendingReviews) {
        this.abacClient = abacClient;
        this.maxPendingReviews = maxPendingReviews;
    }

    /** 下单结果：effect ∈ {PERMIT, DENY, REVIEW}；DENY 时 reason 说明被哪条策略拦截。 */
    public record TradeResult(String effect, boolean permitted, String reason,
                              String policyName, Long reviewId, TradeExecution execution) {
        public static TradeResult permit(TradeExecution execution) {
            return new TradeResult("PERMIT", true, null, execution.policyName(), null, execution);
        }

        public static TradeResult deny(String reason, String policyName) {
            return new TradeResult("DENY", false, reason, policyName, null, null);
        }

        public static TradeResult review(Long reviewId, String policyName) {
            return new TradeResult("REVIEW", false, null, policyName, reviewId, null);
        }
    }

    public TradeResult execute(TradeRequest req, Map<String, Object> subject) {
        if (req == null || req.amount() <= 0) {
            throw new IllegalArgumentException("交易金额必须大于 0");
        }
        String username = String.valueOf(subject.getOrDefault("username", "anonymous"));
        double amount = req.amount();
        Accumulator acc = accumulator(username);
        double cumulativeAfter = acc.amountTotal + amount;

        // PDP 契约：资源属性必须放在 attributes 包内（ResourceRef(type, id, attributes)），
        // 平铺在顶层会被反序列化丢弃，导致策略条件全部不命中。
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("amount", amount);
        attributes.put("channel", nullToStr(req.channel()));
        attributes.put("region", nullToStr(req.region()));
        attributes.put("instrument", nullToStr(req.instrument()));
        attributes.put("cumulativeAmount", acc.amountTotal);
        attributes.put("cumulativeCount", acc.count);
        attributes.put("cumulativeAfter", cumulativeAfter);
        Map<String, Object> resource = Map.of("type", "TRADE", "attributes", attributes);

        AbacClient.Decision d = abacClient.decide(subject, resource, "EXECUTE");
        return switch (d.effect()) {
            case "PERMIT" -> {
                acc.add(amount);
                TradeExecution execution = new TradeExecution(idSeq.getAndIncrement(), username,
                        amount, nullToStr(req.channel()), nullToStr(req.region()),
                        nullToStr(req.instrument()), "PERMIT", d.policyName(), System.currentTimeMillis());
                trades.add(execution);
                log.info("[risk] PERMIT {} {} {} ({} )", username, amount, req.channel(), d.policyName());
                yield TradeResult.permit(execution);
            }
            case "REVIEW" -> {
                long pending = reviews.values().stream().filter(t -> t.status() == ReviewTask.Status.PENDING).count();
                if (pending >= maxPendingReviews) {
                    yield TradeResult.deny("复核队列已满，请稍后重试", d.policyName());
                }
                long id = idSeq.getAndIncrement();
                ReviewTask task = new ReviewTask(id, username, amount,
                        nullToStr(req.channel()), nullToStr(req.region()),
                        nullToStr(req.instrument()), d.policyName(), System.currentTimeMillis());
                reviews.put(id, task);
                log.info("[risk] REVIEW {} {} {} -> task#{} ({})", username, amount, req.channel(), id, d.policyName());
                yield TradeResult.review(id, d.policyName());
            }
            default -> {
                log.info("[risk] DENY {} {} {} ({}: {})", username, amount, req.channel(), d.policyName(), d.reason());
                yield TradeResult.deny(d.reason() == null ? "denied by policy" : d.reason(), d.policyName());
            }
        };
    }

    /** 人工批准复核单：仅 manager/admin（controller 层已鉴权）。批准后计入当日累计并转成交。 */
    public TradeResult approveReview(long id, String actor) {
        ReviewTask t = requirePending(id);
        t.decide(ReviewTask.Status.APPROVED, actor);
        accumulator(t.username()).add(t.amount());
        TradeExecution execution = new TradeExecution(idSeq.getAndIncrement(), t.username(),
                t.amount(), t.channel(), t.region(), t.instrument(), "REVIEW_APPROVED",
                t.policyName(), System.currentTimeMillis());
        trades.add(execution);
        log.info("[risk] review#{} APPROVED by {} (amount {})", id, actor, t.amount());
        return TradeResult.permit(execution);
    }

    /** 人工拒绝复核单。 */
    public void rejectReview(long id, String actor) {
        ReviewTask t = requirePending(id);
        t.decide(ReviewTask.Status.REJECTED, actor);
        log.info("[risk] review#{} REJECTED by {}", id, actor);
    }

    /** 待复核列表（含已处理，方便审计复盘）。 */
    public List<ReviewTask> listReviews() {
        List<ReviewTask> all = new ArrayList<>(reviews.values());
        all.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        return all;
    }

    public List<TradeExecution> myTrades(String username) {
        return trades.stream().filter(t -> t.username().equals(username)).toList();
    }

    /** 当日累计（按日期 + 主体）。 */
    public Map<String, Object> stats(String username) {
        Accumulator acc = accumulator(username);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", LocalDate.now().toString());
        m.put("username", username);
        m.put("count", acc.count);
        m.put("amountTotal", acc.amountTotal);
        m.put("limitAmount", 100000);
        m.put("limitCount", null);
        return m;
    }

    private ReviewTask requirePending(long id) {
        ReviewTask t = reviews.get(id);
        if (t == null) {
            throw new IllegalArgumentException("复核任务不存在: " + id);
        }
        if (t.status() != ReviewTask.Status.PENDING) {
            throw new IllegalArgumentException("复核任务已处理: " + id + " (" + t.status() + ")");
        }
        return t;
    }

    private Accumulator accumulator(String username) {
        String key = LocalDate.now() + ":" + username;
        return accumulators.computeIfAbsent(key, k -> new Accumulator());
    }

    private static String nullToStr(String s) {
        return s == null || s.isBlank() ? "UNKNOWN" : s.trim().toUpperCase();
    }

    private static final class Accumulator {
        private int count;
        private double amountTotal;

        synchronized void add(double amount) {
            count++;
            amountTotal += amount;
        }
    }
}
