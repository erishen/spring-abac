package com.example.abac.audit.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 审计日志（append-only）：记录每一次经网关 PEP 裁决的请求。
 * 相比 RBAC 版多记了命中的策略（policyId / policyName / reason），
 * 便于回答"这条访问是被哪条策略放/挡的"。
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 32)
    private String traceId;

    @Column(nullable = false)
    private String actor;

    /** 动作：资源类型:动作（如 DOCUMENT:READ），或 http 动作（如 GET /api/me）。 */
    @Column(nullable = false, length = 120)
    private String action;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(nullable = false, length = 255)
    private String path;

    private Long resourceId;

    /** 裁决结果：ALLOW / DENY。 */
    @Column(nullable = false, length = 10)
    private String decision;

    private Integer status;

    /** 命中的策略（无命中时为 null，如 default deny）。 */
    private Long policyId;

    @Column(length = 120)
    private String policyName;

    @Column(length = 500)
    private String reason;

    @Column(length = 500)
    private String detail;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getPolicyId() {
        return policyId;
    }

    public void setPolicyId(Long policyId) {
        this.policyId = policyId;
    }

    public String getPolicyName() {
        return policyName;
    }

    public void setPolicyName(String policyName) {
        this.policyName = policyName;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
