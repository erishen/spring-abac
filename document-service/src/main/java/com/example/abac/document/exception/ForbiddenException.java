package com.example.abac.document.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 业务服务内的第二道闸：网关已裁过一次，服务内再按完整属性裁一次（行级/字段级）。 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ForbiddenException(String message) {
        super(message);
    }
}
