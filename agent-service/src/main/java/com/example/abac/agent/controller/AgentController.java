package com.example.abac.agent.controller;

import com.example.abac.agent.model.ToolCallRequest;
import com.example.abac.agent.model.ToolExecution;
import com.example.abac.agent.service.AgentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Agent 前置校验接口。用户属性由网关从 JWT 解析后经 X-User / X-Attr-* 透传；
 * agentId/trust/session 由模拟的 Agent 运行时在请求体内声明（演示环境）。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    /** 工具调用前置校验：PERMIT 执行 / DENY 拒绝 / REVIEW 转人工复核。 */
    @PostMapping("/tools")
    public ResponseEntity<?> checkTool(@RequestBody(required = false) ToolCallRequest req,
                                       @RequestParam(value = "session", required = false) String sessionId,
                                       @RequestHeader(value = "X-User", required = false) String user,
                                       @RequestHeader(value = "X-Attr-Title", required = false) String title) {
        Map<String, Object> subject = new LinkedHashMap<>();
        subject.put("username", user == null ? "anonymous" : user);
        if (title != null) {
            subject.put("title", title);
        }
        if (req != null) {
            subject.put("agentId", req.agentId() == null ? "unknown" : req.agentId());
            subject.put("trust", req.trust() == null ? "medium" : req.trust());
        }
        AgentService.ToolResult r = agentService.execute(req, subject, sessionId);
        if (!r.permitted() && "DENY".equals(r.effect())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "denied by agent policy");
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

    /** 我的工具调用记录（按主体过滤）。 */
    @GetMapping("/tools")
    public List<ToolExecution> myTools(@RequestHeader(value = "X-User", required = false) String user) {
        return agentService.myExecutions(user == null ? "anonymous" : user);
    }

    /** 会话统计（外联/发信等次数 + 上限）。 */
    @GetMapping("/session")
    public Map<String, Object> session(@RequestParam(value = "session", required = false) String sessionId) {
        return agentService.sessionStats(sessionId);
    }

    /** 复核任务列表（仅 manager / admin）。 */
    @GetMapping("/reviews")
    public ResponseEntity<?> reviews(@RequestHeader(value = "X-Attr-Title", required = false) String title) {
        if (!canReview(title)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "仅 manager / admin 可查看复核任务"));
        }
        return ResponseEntity.ok(agentService.listReviews());
    }

    /** 批准复核（仅 manager / admin）：批准后执行并计入会话统计。 */
    @PostMapping("/reviews/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable long id,
                                     @RequestHeader(value = "X-User", required = false) String user,
                                     @RequestHeader(value = "X-Attr-Title", required = false) String title) {
        if (!canReview(title)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "仅 manager / admin 可批准复核"));
        }
        AgentService.ToolResult r = agentService.approveReview(id, user == null ? "?" : user);
        return ResponseEntity.ok(Map.of("effect", "APPROVED", "execution", r.execution()));
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
        agentService.rejectReview(id, user == null ? "?" : user);
        return ResponseEntity.ok(Map.of("effect", "REJECTED"));
    }

    /** Agent 复核操作收敛到 manager / admin（与风控一致的分工语义）。 */
    private static boolean canReview(String title) {
        return "manager".equals(title) || "admin".equals(title);
    }
}
