package com.example.abac.auth.dto;

/** 认证服务对外 DTO（用 record 简化）。 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /**
     * 注册只收账号密码：主体属性是授权依据，不能让用户自报。
     * 新账号落库为默认低权限（ENG / clearance=1 / CN / engineer），
     * 后续由管理员在"用户属性"面板调整（USR-60）。
     */
    public record RegisterRequest(String username, String password) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record TokenResponse(String token) {
    }

    /** 当前登录者：身份 + 主体属性（前端据此展示"我有哪些属性"）。 */
    public record UserInfo(String username, String department, Integer clearance,
                           String region, String title) {
    }

    public record UserDto(Long id, String username, String department, Integer clearance,
                          String region, String title, Long createdAt) {
    }

    /** 修改主体属性：改属性即改权限，无需动任何角色/权限表。 */
    public record UpdateAttributesRequest(String department, Integer clearance,
                                          String region, String title) {
    }
}
