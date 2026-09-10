package com.example.abac.audit.dto;

public record AuditStatsDto(long total, long allows, long denies) {
}
