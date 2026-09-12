package com.example.abac.risk.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

/**
 * 人工复核任务（REVIEW 第三态）：超限/高风险交易不直接拒绝也不放行，
 * 落成待办由 manager / admin 人工批准或拒绝。批准才计入当日累计额度。
 *
 * 注意：访问器是 record 风格的 id()/username()，Jackson 默认 bean 协议不识别，
 * 需按字段序列化（否则列表接口 500）。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ReviewTask {

    public enum Status { PENDING, APPROVED, REJECTED }

    private final long id;
    private final String username;
    private final double amount;
    private final String channel;
    private final String region;
    private final String instrument;
    private final String policyName;
    private final long createdAt;
    private Status status = Status.PENDING;
    private String decidedBy;
    private Long decidedAt;

    public ReviewTask(long id, String username, double amount, String channel,
                      String region, String instrument, String policyName, long createdAt) {
        this.id = id;
        this.username = username;
        this.amount = amount;
        this.channel = channel;
        this.region = region;
        this.instrument = instrument;
        this.policyName = policyName;
        this.createdAt = createdAt;
    }

    public long id() { return id; }
    public String username() { return username; }
    public double amount() { return amount; }
    public String channel() { return channel; }
    public String region() { return region; }
    public String instrument() { return instrument; }
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
