package com.example.abac.audit.controller;

import com.example.abac.audit.dto.AuditEvent;
import com.example.abac.audit.dto.AuditPageDto;
import com.example.abac.audit.dto.AuditStatsDto;
import com.example.abac.audit.service.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class AuditController {

    /** 网关内部发射审计事件时携带的私有头；缺失或非法的写入请求一律拒绝，防伪造。 */
    private static final String INTERNAL_HEADER = "X-Internal-Audit";

    private final String internalValue;
    private final AuditService auditService;

    public AuditController(AuditService auditService,
                           @Value("${app.internal-secret:dev-only-internal-secret-change-me}") String internalSecret) {
        this.auditService = auditService;
        this.internalValue = internalSecret;
    }

    /** 写入审计事件：仅允许网关经服务发现直连调用并携带私有头。 */
    @PostMapping("/audit")
    @ResponseStatus(HttpStatus.OK)
    public void append(@RequestHeader(value = INTERNAL_HEADER, required = false) String marker,
                       @RequestBody AuditEvent event) {
        if (!internalValue.equals(marker)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "audit write not allowed");
        }
        auditService.record(event);
    }

    /** 概览统计（需 AUDIT:READ 权限，由网关 PEP 按策略裁决）。 */
    @GetMapping("/audit/stats")
    public AuditStatsDto stats() {
        return auditService.stats();
    }

    /** 分页查询（支持按裁决结果 / 链路 id / 操作人过滤）。 */
    @GetMapping("/audit")
    public AuditPageDto list(@RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size,
                             @RequestParam(required = false) String decision,
                             @RequestParam(required = false) String traceId,
                             @RequestParam(required = false) String actor) {
        return auditService.paged(page, size, decision, traceId, actor);
    }
}
