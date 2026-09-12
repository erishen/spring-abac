package com.example.abac.agent.exception;

import com.example.abac.common.web.GlobalExceptionHandlerSupport;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 全局异常处理，仅作用于本服务的 controller 包（逻辑在 common 基类）。 */
@RestControllerAdvice(basePackages = "com.example.abac.agent.controller")
public class GlobalExceptionHandler extends GlobalExceptionHandlerSupport {
}
