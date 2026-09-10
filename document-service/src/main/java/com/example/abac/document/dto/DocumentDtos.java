package com.example.abac.document.dto;

import java.util.List;
import java.util.Map;

/** 文档服务对外 DTO。 */
public final class DocumentDtos {

    private DocumentDtos() {
    }

    public record DocumentDto(Long id, String title, String content, String owner,
                              String department, String classification, String status,
                              int requiredClearance, Long createdAt) {
    }

    public record CreateDocumentRequest(String title, String content, String department,
                                        String classification, String status) {
    }

    public record UpdateDocumentRequest(String title, String content, String department,
                                        String classification, String status) {
    }

    /** 手动分页结果（先按策略过滤、再分页，故不用 Spring Data 的 Page）。 */
    public record DocumentPage(List<DocumentDto> content, long totalElements, int number,
                               int size, int totalPages) {
    }

    /** PIP 回源返回的资源属性（供 PDP 判定）。 */
    public record ResourceAttributes(Map<String, Object> attributes) {
    }
}
