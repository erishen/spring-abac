package com.example.abac.agent.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

/**
 * Agent 工具调用的人工复核任务（REVIEW 第三态）：群发邮件、大额转账、疑似高危代码等
 * 转人工确认，manager/admin 批准后才执行并计入会话统计。
 *
 * 注意：访问器是 record 风格的 id()/username()，Jackson 默认 bean 协议不识别，
 * 需按字段序列化（否则列表接口 500）。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class AgentReviewTask {

    public enum Status { PENDING, APPROVED, REJECTED }

    private final long id;
    private final String username;
    private final String agentId;
    private final String toolType;
    private final String operation;
    private final String summary;
    private final String policyName;
    private final long createdAt;
    private Status status = Status.PENDING;
    private String decidedBy;
    private Long decidedAt;

    public AgentReviewTask(long id, String username, String agentId, String toolType,
                           String operation, String summary, String policyName, long createdAt) {
        this.id = id;
        this.username = username;
        this.agentId = agentId;
        this.toolType = toolType;
        this.operation = operation;
        this.summary = summary;
        this.policyName = policyName;
        this.createdAt = createdAt;
    }

    public long id() { return id; }
    public String username() { return username; }
    public String agentId() { return agentId; }
    public String toolType() { return toolType; }
    public String operation() { return operation; }
    public String summary() { return summary; }
    public String policyName() { return policyName; }
    public long createdAt() { return createdAt; }
    public Status status() { return status; }
    public String decidedBy() { return decidedBy; }
    public Long decidedAt() { return decidedAt; }

    public void decide(Status s, String by) {
        this.status = s;
        this.decidedBy = by;
        this.decidedAt = System.currentTimeMillis();
    }
}
