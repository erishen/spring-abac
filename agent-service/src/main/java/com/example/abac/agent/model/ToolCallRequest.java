package com.example.abac.agent.model;

import java.util.Map;

/**
 * Agent 工具调用请求。模拟 Agent 运行时在调用工具前提交的元数据：
 * agentId（哪个 Agent）、trust（信任等级 high/medium/low，由运行时声明）、
 * toolType（工具域 WEB/EMAIL/PAYMENT/FS/CODE）、operation（FETCH/SEND/...）、
 * params（工具参数：url / recipientCount / amount / path / code）。
 *
 * 注意：真实生产环境中 agentId/trust/session 应由平台侧下发，不能由客户端自报；
 * 本演示由前端选择，仅展示 PDP 侧的校验逻辑。
 */
public record ToolCallRequest(
        String agentId,
        String trust,
        String toolType,
        String operation,
        Map<String, Object> params
) {
}
