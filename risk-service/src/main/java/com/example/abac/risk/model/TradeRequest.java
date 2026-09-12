package com.example.abac.risk.model;

/**
 * 模拟下单请求。主体属性不在此列：一律由网关从 JWT 解析后经 X-User / X-Attr-*
 * 请求头透传（网关是唯一入口，业务服务不信任客户端自报属性）。
 */
public record TradeRequest(
        double amount,
        String channel,   // WEB / MOBILE / APP
        String region,    // 交易发生地 CN / US
        String instrument // 标的，如 AAPL、00700
) {
}
