package com.example.abac.common.util;

/** JWT 校验失败（签名错 / 过期 / 格式坏）。由 JwtUtil.verify 抛出，调用方按 401 处理。 */
public class JwtException extends RuntimeException {

    public JwtException(String message) {
        super(message);
    }
}
