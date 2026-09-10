package com.example.abac.audit.dto;

/**
 * 网关发射过来的审计事件（请求体），字段全部来自网关 PEP 的裁决结果。
 * 用 record：字段名即 JSON 键，构造即校验，省掉一堆样板 getter。
 */
public record AuditEvent(String traceId, String actor, String action, String method,
                         String path, Long resourceId, String decision, Integer status,
                         String detail, Long policyId, String policyName, String reason) {
}
