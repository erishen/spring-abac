package com.example.abac.abac.model;

/**
 * 策略效果：PERMIT 放行 / DENY 拒绝 / REVIEW 转人工复核（风控第三态）。
 * 裁决合并时按 DENY > REVIEW > PERMIT 的优先级取最终效果（deny-override；
 * REVIEW 用于"够格但需人工确认"的高风险动作，如超限交易）。
 */
public enum Effect {
    PERMIT,
    DENY,
    REVIEW
}
