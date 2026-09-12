package com.example.abac.auth.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归测试：GlobalExceptionHandler 的兜底处理器必须尊重业务异常上的 @ResponseStatus。
 * 曾出现回归——advice 新增 @ExceptionHandler(Exception.class) 后，@ResponseStatus
 * 语义（401/404/409）全部被 500 吞掉（登录失败、无效 Token 均返回 500）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void authFailedException_maps_to_401() {
        var resp = handler.fallback(new AuthFailedException("用户名或密码错误"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).containsEntry("error", "用户名或密码错误");
    }

    @Test
    void notFoundException_maps_to_404() {
        var resp = handler.fallback(new NotFoundException("user not found: x"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void conflictException_maps_to_409() {
        var resp = handler.fallback(new ConflictException("用户名已存在: x"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void unexpectedException_maps_to_500_without_leaking_message() {
        var resp = handler.fallback(new IllegalStateException("secret detail"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody()).containsEntry("error", "internal error");
    }
}
