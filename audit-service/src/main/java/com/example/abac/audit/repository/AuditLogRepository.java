package com.example.abac.audit.repository;

import com.example.abac.audit.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    long countByDecision(String decision);

    /** 按裁决结果与链路 id 过滤（null 参数表示不过滤）。 */
    @Query("select a from AuditLog a where (:decision is null or a.decision = :decision)"
            + " and (:traceId is null or a.traceId = :traceId)"
            + " and (:actor is null or a.actor = :actor)")
    Page<AuditLog> search(@Param("decision") String decision,
                          @Param("traceId") String traceId,
                          @Param("actor") String actor,
                          Pageable pageable);
}
