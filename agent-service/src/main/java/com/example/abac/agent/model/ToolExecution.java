package com.example.abac.agent.model;

/** 已放行的工具调用记录（内存演示）。effect 记录当时裁决：PERMIT / REVIEW_APPROVED。 */
public record ToolExecution(
        long id,
        String username,
        String agentId,
        String toolType,
        String operation,
        String summary,
        String effect,
        String policyName,
        long createdAt
) {
}
