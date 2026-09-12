package com.example.abac.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 已认证但无权限（如非 admin 尝试修改用户属性），语义区别于 401 未认证。 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class AuthForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuthForbiddenException(String message) {
        super(message);
    }
}
