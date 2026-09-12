package com.example.abac.risk.exception;

import com.example.abac.common.web.GlobalExceptionHandlerSupport;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理，仅作用于本服务的 controller 包（逻辑在 common 基类）。
 * 故意不拦截 actuator 端点（如 /health）：actuator 自带健康检查与错误响应。
 */
@RestControllerAdvice(basePackages = "com.example.abac.risk.controller")
public class GlobalExceptionHandler extends GlobalExceptionHandlerSupport {
}
