package com.example.abac.auth.service;

import com.example.abac.auth.dto.AuthDtos.UpdateAttributesRequest;
import com.example.abac.auth.dto.AuthDtos.UserDto;
import com.example.abac.auth.exception.AuthFailedException;
import com.example.abac.auth.exception.ConflictException;
import com.example.abac.auth.exception.NotFoundException;
import com.example.abac.auth.model.User;
import com.example.abac.auth.repository.UserRepository;
import com.example.abac.common.util.JwtUtil;
import com.example.abac.auth.util.PasswordUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    public AuthService(UserRepository userRepository,
                       @Value("${app.jwt-secret}") String secret,
                       @Value("${app.jwt-ttl}") long ttlMillis) {
        this.userRepository = userRepository;
        this.jwtUtil = new JwtUtil(secret, ttlMillis);
    }

    public UserDto register(String username, String password, String department,
                            Integer clearance, String region, String title) {
        if (username == null || username.isBlank() || password == null || password.length() < 6) {
            throw new IllegalArgumentException("用户名不能为空，密码至少 6 位");
        }
        if (userRepository.findByUsername(username).isPresent()) {
            throw new ConflictException("用户名已存在: " + username);
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(PasswordUtil.hash(password));
        user.setDepartment(defaultStr(department, "ENG"));
        user.setClearance(clearance == null ? 1 : clamp(clearance));
        user.setRegion(defaultStr(region, "CN"));
        user.setTitle(defaultStr(title, "engineer"));
        user.setCreatedAt(System.currentTimeMillis());
        return toDto(userRepository.save(user));
    }

    public String login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new AuthFailedException("用户名或密码错误"));
        if (!PasswordUtil.matches(password, user.getPasswordHash())) {
            throw new AuthFailedException("用户名或密码错误");
        }
        return jwtUtil.generate(username, attributes(user));
    }

    /** 校验 JWT，返回其中的 Claims（subject + 主体属性）；失败抛 AuthFailedException(401)。 */
    public JwtUtil.Claims verify(String token) {
        try {
            return jwtUtil.verify(token);
        } catch (RuntimeException e) {
            throw new AuthFailedException(e.getMessage());
        }
    }

    public UserDto me(String username) {
        return toDto(require(username));
    }

    public List<UserDto> listUsers() {
        return userRepository.findAll().stream().map(AuthService::toDto).toList();
    }

    /** 更新主体属性（下一次登录签发的新 JWT 即带上新属性，判定结果随之改变）。 */
    public UserDto updateAttributes(String username, UpdateAttributesRequest req) {
        User user = require(username);
        if (req.department() != null && !req.department().isBlank()) {
            user.setDepartment(req.department());
        }
        if (req.clearance() != null) {
            user.setClearance(clamp(req.clearance()));
        }
        if (req.region() != null && !req.region().isBlank()) {
            user.setRegion(req.region());
        }
        if (req.title() != null && !req.title().isBlank()) {
            user.setTitle(req.title());
        }
        return toDto(userRepository.save(user));
    }

    /** 把用户属性拍平成 JWT 里的 attrs（PDP 直接消费）。 */
    public Map<String, Object> attributes(User user) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("username", user.getUsername());
        attrs.put("department", user.getDepartment());
        attrs.put("clearance", user.getClearance());
        attrs.put("region", user.getRegion());
        attrs.put("title", user.getTitle());
        return attrs;
    }

    private User require(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("user not found: " + username));
    }

    private static int clamp(int v) {
        return Math.max(1, Math.min(5, v));
    }

    private static String defaultStr(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static UserDto toDto(User u) {
        return new UserDto(u.getId(), u.getUsername(), u.getDepartment(), u.getClearance(),
                u.getRegion(), u.getTitle(), u.getCreatedAt());
    }
}
