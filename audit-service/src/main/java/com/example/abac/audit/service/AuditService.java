package com.example.abac.audit.service;

import com.example.abac.audit.dto.AuditEvent;
import com.example.abac.audit.dto.AuditLogDto;
import com.example.abac.audit.dto.AuditPageDto;
import com.example.abac.audit.dto.AuditStatsDto;
import com.example.abac.audit.model.AuditLog;
import com.example.abac.audit.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /** 落库一条审计事件（append-only，无更新/删除入口）。 */
    public void record(AuditEvent event) {
        AuditLog log = new AuditLog();
        log.setTraceId(event.traceId());
        log.setActor(event.actor() == null ? "anonymous" : event.actor());
        log.setAction(event.action() == null ? "" : event.action());
        log.setMethod(event.method() == null ? "" : event.method());
        log.setPath(event.path() == null ? "" : event.path());
        log.setResourceId(event.resourceId());
        log.setDecision(event.decision() == null ? "DENY" : event.decision());
        log.setStatus(event.status());
        log.setDetail(event.detail());
        log.setPolicyId(event.policyId());
        log.setPolicyName(event.policyName());
        log.setReason(event.reason());
        repository.save(log);
    }

    public AuditPageDto paged(int page, int size, String decision, String traceId, String actor) {
        Pageable pg = PageRequest.of(Math.max(page, 0), Math.max(size, 1));
        Page<AuditLogDto> p = repository
                .search(blankToNull(decision), blankToNull(traceId), blankToNull(actor), pg)
                .map(AuditService::toDto);
        return new AuditPageDto(p.getContent(), p.getTotalElements(), p.getNumber(),
                p.getSize(), p.getTotalPages());
    }

    public AuditStatsDto stats() {
        return new AuditStatsDto(repository.count(),
                repository.countByDecision("ALLOW"),
                repository.countByDecision("DENY"));
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static AuditLogDto toDto(AuditLog a) {
        return new AuditLogDto(a.getId(), a.getTraceId(), a.getActor(), a.getAction(),
                a.getMethod(), a.getPath(), a.getResourceId(), a.getDecision(), a.getStatus(),
                a.getPolicyId(), a.getPolicyName(), a.getReason(), a.getDetail(),
                a.getCreatedAt());
    }
}
