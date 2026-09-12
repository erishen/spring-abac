package com.example.abac.risk.controller;

import com.example.abac.risk.model.ReviewTask;
import com.example.abac.risk.model.TradeExecution;
import com.example.abac.risk.model.TradeRequest;
import com.example.abac.risk.service.RiskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易风控接口。主体属性由网关从 JWT 解析后放在 X-User / X-Attr-* 请求头透传，
 * 本服务据此组装 subject 属性包再向 PDP 提问（网关是唯一入口，故可信任这些头）。
 */
@RestController
@RequestMapping("/api/trades")
public class RiskController {

    private final RiskService riskService;

    public RiskController(RiskService riskService) {
        this.riskService = riskService;
    }

    /** 模拟下单：前置校验（PERMIT 成交 / DENY 拒绝 / REVIEW 转人工复核）。 */
    @PostMapping
    public ResponseEntity<?> execute(@RequestBody(required = false) TradeRequest req,
                                     @RequestHeader(value = "X-User", required = false) String user,
                                     @RequestHeader(value = "X-Attr-Title", required = false) String title) {
        RiskService.TradeResult r = riskService.execute(req, subject(user, title));
        if (!r.permitted() && "DENY".equals(r.effect())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "denied by risk policy");
            body.put("reason", r.reason());
            body.put("policyName", r.policyName());
            body.put("effect", "DENY");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("effect", r.effect());
        body.put("permitted", r.permitted());
        body.put("policyName", r.policyName());
        if (r.reviewId() != null) {
            body.put("reviewId", r.reviewId());
            body.put("message", "已转入人工复核，等待 manager/admin 审批");
        }
        if (r.execution() != null) {
            body.put("execution", r.execution());
        }
        return ResponseEntity.ok(body);
    }

    /** 我的交易记录（按主体过滤）。 */
    @GetMapping
    public List<TradeExecution> myTrades(@RequestHeader(value = "X-User", required = false) String user) {
        return riskService.myTrades(user == null ? "anonymous" : user);
    }

    /** 我的当日累计额度/次数。 */
    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestHeader(value = "X-User", required = false) String user) {
        return riskService.stats(user == null ? "anonymous" : user);
    }

    /** 复核任务列表（仅 manager / admin）。 */
    @GetMapping("/reviews")
    public ResponseEntity<?> reviews(@RequestHeader(value = "X-Attr-Title", required = false) String title) {
        if (!canReview(title)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "仅 manager / admin 可查看复核任务"));
        }
        return ResponseEntity.ok(riskService.listReviews());
    }

    /** 批准复核（仅 manager / admin）：批准后计入当日累计并转成交。 */
    @PostMapping("/reviews/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable long id,
                                     @RequestHeader(value = "X-User", required = false) String user,
                                     @RequestHeader(value = "X-Attr-Title", required = false) String title) {
        if (!canReview(title)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "仅 manager / admin 可批准复核"));
        }
        RiskService.TradeResult r = riskService.approveReview(id, user == null ? "?" : user);
        return ResponseEntity.ok(Map.of(
                "effect", "APPROVED",
                "execution", r.execution()));
    }

    /** 拒绝复核（仅 manager / admin）。 */
    @PostMapping("/reviews/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable long id,
                                    @RequestHeader(value = "X-User", required = false) String user,
                                    @RequestHeader(value = "X-Attr-Title", required = false) String title) {
        if (!canReview(title)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "仅 manager / admin 可拒绝复核"));
        }
        riskService.rejectReview(id, user == null ? "?" : user);
        return ResponseEntity.ok(Map.of("effect", "REJECTED"));
    }

    /** 风控复核操作收敛到 manager / admin（与文档域"发布"一致的分工语义）。 */
    private static boolean canReview(String title) {
        return "manager".equals(title) || "admin".equals(title);
    }

    /** 从网关透传头组装 PDP 的 subject 属性包。 */
    private static Map<String, Object> subject(String user, String title) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("username", user == null ? "anonymous" : user);
        if (title != null) {
            s.put("title", title);
        }
        return s;
    }
}
