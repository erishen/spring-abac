package com.example.abac.abac.exception;

import com.example.abac.common.web.GlobalExceptionHandlerSupport;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理，仅作用于本服务的 controller 包（逻辑在 common 基类，避免三份复制）。
 * 故意不拦截 actuator 端点（如 /health）：actuator 自带健康检查与错误响应，
 * 若被笼统的 Exception 处理器接住，会把"健康组件未就绪"之类本应返回 200/503
 * 的状态误判成 500，既误导探针又掩盖真实错误。
 */
@RestControllerAdvice(basePackages = "com.example.abac.abac.controller")
public class GlobalExceptionHandler extends GlobalExceptionHandlerSupport {
}
