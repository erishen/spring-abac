package com.example.abac.audit.dto;

import java.time.LocalDateTime;

public record AuditLogDto(Long id, String traceId, String actor, String action, String method,
                          String path, Long resourceId, String decision, Integer status,
                          Long policyId, String policyName, String reason, String detail,
                          LocalDateTime createdAt) {
}
