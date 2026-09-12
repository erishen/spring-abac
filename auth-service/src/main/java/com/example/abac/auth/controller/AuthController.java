package com.example.abac.auth.controller;

import com.example.abac.auth.dto.AuthDtos.LoginRequest;
import com.example.abac.auth.dto.AuthDtos.RegisterRequest;
import com.example.abac.auth.dto.AuthDtos.TokenResponse;
import com.example.abac.auth.dto.AuthDtos.UpdateAttributesRequest;
import com.example.abac.auth.dto.AuthDtos.UserDto;
import com.example.abac.auth.dto.AuthDtos.UserInfo;
import com.example.abac.auth.exception.AuthFailedException;
import com.example.abac.auth.service.AuthService;
import com.example.abac.common.util.JwtUtil;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public UserDto register(@RequestBody RegisterRequest req) {
        return authService.register(req.username(), req.password());
    }

    @PostMapping("/login")
    public TokenResponse login(@RequestBody LoginRequest req) {
        return new TokenResponse(authService.login(req.username(), req.password()));
    }

    /** 当前登录者及其主体属性（属性即策略输入，前端据此解释"为什么能/不能"）。 */
    @GetMapping("/me")
    public UserInfo me(@RequestHeader("Authorization") String authorization) {
        JwtUtil.Claims claims = authService.verify(extractToken(authorization));
        Map<String, Object> a = claims.attrs();
        return new UserInfo(
                claims.subject(),
                str(a.get("department")),
                a.get("clearance") instanceof Number n ? n.intValue() : null,
                str(a.get("region")),
                str(a.get("title")));
    }

    /** 用户列表（含属性）。谁能看由网关 PEP + ABAC PDP 按策略裁决。 */
    @GetMapping("/users")
    public List<UserDto> listUsers() {
        return authService.listUsers();
    }

    /**
     * 修改主体属性：只允许 admin（网关 USR-60 + 本服务业务层双校验）。
     * 属性是授权依据，任何登录用户都改等于没有门禁。
     */
    @PutMapping("/users/{username}/attributes")
    public UserDto updateAttributes(@PathVariable String username,
                                    @RequestHeader("Authorization") String authorization,
                                    @RequestBody UpdateAttributesRequest req) {
        JwtUtil.Claims actor = authService.verify(extractToken(authorization));
        return authService.updateAttributes(username, actor, req);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private String extractToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new AuthFailedException("缺少 Bearer Token");
        }
        return authorization.substring(7);
    }
}
