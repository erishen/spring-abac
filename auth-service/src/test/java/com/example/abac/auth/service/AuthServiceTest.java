package com.example.abac.auth.service;

import com.example.abac.auth.dto.AuthDtos.UpdateAttributesRequest;
import com.example.abac.auth.dto.AuthDtos.UserDto;
import com.example.abac.auth.exception.AuthForbiddenException;
import com.example.abac.auth.exception.ConflictException;
import com.example.abac.auth.model.User;
import com.example.abac.auth.repository.UserRepository;
import com.example.abac.common.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 隐私与权限基线测试：注册不接收主体属性（防自报提权），
 * 属性修改只允许 admin（防任意篡改授权依据）。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    private AuthService service;

    private static final JwtUtil.Claims ADMIN =
            new JwtUtil.Claims("admin", Map.of("title", "admin"));
    private static final JwtUtil.Claims ENGINEER =
            new JwtUtil.Claims("alice", Map.of("title", "engineer"));

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, "test-secret", 86_400_000L);
    }

    private static User user(String username) {
        User u = new User();
        u.setId(1L);
        u.setUsername(username);
        u.setPasswordHash("salt:hash");
        u.setDepartment("ENG");
        u.setClearance(4);
        u.setRegion("CN");
        u.setTitle("manager");
        return u;
    }

    // ---------- 注册：属性不接收自报，一律默认低权限 ----------

    @Test
    void register_ignoresSelfReportedAttributes_andDefaultsToLowPrivilege() {
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 旧接口曾允许传 clearance/title；新签名只剩账号密码，外部传的属性根本无处安放
        UserDto dto = service.register("newbie", "secret123");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertEquals("newbie", saved.getUsername());
        assertEquals("ENG", saved.getDepartment());
        assertEquals(1, saved.getClearance());   // 最低密级
        assertEquals("CN", saved.getRegion());
        assertEquals("engineer", saved.getTitle()); // 普通员工，绝非 admin
        assertEquals("newbie", dto.username());
    }

    @Test
    void register_duplicateUsernameThrowsConflict() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user("admin")));
        assertThrows(ConflictException.class, () -> service.register("admin", "secret123"));
    }

    @Test
    void register_weakPasswordRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.register("u", "123"));
    }

    // ---------- 属性修改：仅 admin，业务层第二道校验 ----------

    @Test
    void updateAttributes_nonAdminThrowsForbidden() {
        assertThrows(AuthForbiddenException.class,
                () -> service.updateAttributes("alice", ENGINEER,
                        new UpdateAttributesRequest("EXEC", 5, "CN", "admin")));
    }

    @Test
    void updateAttributes_adminCanModifyTargetUser() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user("alice")));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserDto dto = service.updateAttributes("alice", ADMIN,
                new UpdateAttributesRequest("EXEC", 5, "CN", "admin"));

        assertEquals("EXEC", dto.department());
        assertEquals(5, dto.clearance());
        assertEquals("admin", dto.title());
    }

    @Test
    void updateAttributes_clearanceIsClamped() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user("alice")));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserDto dto = service.updateAttributes("alice", ADMIN,
                new UpdateAttributesRequest(null, 99, null, null));

        assertEquals(5, dto.clearance()); // 钳制到上限
    }

    @Test
    void updateAttributes_unknownUserThrows() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class,
                () -> service.updateAttributes("ghost", ADMIN,
                        new UpdateAttributesRequest(null, 3, null, null)));
    }
}
