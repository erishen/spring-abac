package com.example.abac.audit.dto;

import java.util.List;

public record AuditPageDto(List<AuditLogDto> content, long totalElements, int number,
                           int size, int totalPages) {
}
