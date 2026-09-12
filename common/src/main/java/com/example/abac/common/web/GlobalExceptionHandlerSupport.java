package com.example.abac.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 全局异常处理的公共逻辑。各服务的 {@code GlobalExceptionHandler} 继承本类，
 * 仅声明 {@code @RestControllerAdvice(basePackages = "...controller")}，避免三份逐字节复制。
 *
 * 故意只作用于各服务自己的 controller 包：actuator 端点（如 /health）自带健康检查与错误响应，
 * 若被这里笼统的 Exception 处理器接住，会把"健康组件未就绪"之类本应返回 200/503 的
 * 状态误判成 500，既误导探针又掩盖真实错误。
 *
 * 兜底 Exception 处理器<b>尊重业务异常上的 @ResponseStatus</b>（如 401/404/409）：
 * advice 一旦接住异常就不会再进 resolver 链，@ResponseStatus 语义会被 500 吞掉，
 * 所以必须在这里显式读取注解；未标注的意外异常才记日志并返回 500。
 */
public abstract class GlobalExceptionHandlerSupport {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandlerSupport.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", msg(e)));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", msg(e)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> fallback(Exception e) {
        ResponseStatus rs = AnnotatedElementUtils.findMergedAnnotation(e.getClass(), ResponseStatus.class);
        if (rs != null) {
            return ResponseEntity.status(rs.value())
                    .body(Map.of("error", msg(e)));
        }
        log.error("未处理的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "internal error"));
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? "" : e.getMessage();
    }
}
