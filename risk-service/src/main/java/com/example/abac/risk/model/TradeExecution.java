package com.example.abac.risk.model;

/** 已成交交易记录（内存演示）。effect 记录当时裁决结果：PERMIT / REVIEW(批准后转正)。 */
public record TradeExecution(
        long id,
        String username,
        double amount,
        String channel,
        String region,
        String instrument,
        String effect,
        String policyName,
        long createdAt
) {
}
