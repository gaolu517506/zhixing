package com.zhixing.service;

import cn.hutool.crypto.digest.BCrypt;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.config.AuthProperties;
import com.zhixing.dao.entity.AuthSession;
import com.zhixing.dao.entity.AuthUser;
import com.zhixing.dao.mapper.AuthSessionMapper;
import com.zhixing.dao.mapper.AuthUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 单测（02 §B1）：首启 setup 空表语义、防枚举统一文案、连续失败锁定、
 * 校验链（过期/禁用）与滑动续期节流。mapper 全 mock，不依赖真库。
 */
class AuthServiceTest {

    private AuthUserMapper userMapper;

    private AuthSessionMapper sessionMapper;

    private AuditRecorder audit;

    private AuthProperties props;

    private AuthService service;

    private final AtomicLong seq = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        userMapper = mock(AuthUserMapper.class);
        sessionMapper = mock(AuthSessionMapper.class);
        audit = mock(AuditRecorder.class);
        props = new AuthProperties();
        service = new AuthService(userMapper, sessionMapper, audit, props);
    }

    private AuthUser activeUser(String username, String password) {
        AuthUser user = new AuthUser();
        user.setId(seq.incrementAndGet());
        user.setUsername(username);
        user.setPasswordHash(BCrypt.hashpw(password));
        user.setStatus("ACTIVE");
        user.setSystemRole("USER");
        return user;
    }

    // ===== setup =====

    @Test
    void needSetup_空表true非空false() {
        when(userMapper.selectCount(isNull())).thenReturn(0L);
        assertThat(service.needSetup()).isTrue();
        when(userMapper.selectCount(isNull())).thenReturn(2L);
        assertThat(service.needSetup()).isFalse();
    }

    @Test
    void setup_空表建ADMIN并签发token() {
        when(userMapper.selectCount(isNull())).thenReturn(0L);
        AuthService.LoginResult result =
                service.setup(new AuthService.SetupRequest("root", "pass-123456"));
        ArgumentCaptor<AuthUser> captor = ArgumentCaptor.forClass(AuthUser.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getSystemRole()).isEqualTo("ADMIN");
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(BCrypt.checkpw("pass-123456", captor.getValue().getPasswordHash())).isTrue();
        assertThat(result.token()).isNotBlank();
        assertThat(result.user().username()).isEqualTo("root");
        verify(sessionMapper).insert(any(AuthSession.class));
    }

    @Test
    void setup_非空表拒绝() {
        when(userMapper.selectCount(isNull())).thenReturn(1L);
        assertThatThrownBy(() -> service.setup(new AuthService.SetupRequest("root", "pass-123456")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
        verify(userMapper, never()).insert(any(AuthUser.class));
    }

    // ===== 登录防枚举与锁定 =====

    @Test
    void 防枚举_用户不存在与密码错误同一文案() {
        when(userMapper.selectOne(any())).thenReturn(null);
        AuthService.LoginRequest unknown = new AuthService.LoginRequest("ghost", "x");

        when(userMapper.selectOne(any())).thenReturn(activeUser("alice", "right"));
        AuthService.LoginRequest wrongPassword = new AuthService.LoginRequest("alice", "wrong");

        assertThatThrownBy(() -> service.login(unknown))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号或密码错误");
        assertThatThrownBy(() -> service.login(wrongPassword))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号或密码错误");
    }

    @Test
    void 连续失败达阈值锁定_正确密码也被拒() {
        AuthUser user = activeUser("bob", "right");
        when(userMapper.selectOne(any())).thenReturn(user);

        for (int i = 0; i < props.getMaxFailedAttempts(); i++) {
            assertThatThrownBy(() -> service.login(new AuthService.LoginRequest("bob", "nope")))
                    .hasMessageContaining("账号或密码错误");
        }
        // 第 6 次：即使密码正确，锁定中
        assertThatThrownBy(() -> service.login(new AuthService.LoginRequest("bob", "right")))
                .hasMessageContaining("锁定");
        verify(sessionMapper, never()).insert(any(AuthSession.class));
    }

    @Test
    void 登录成功签发token且库存哈希() {
        AuthUser user = activeUser("carol", "right");
        when(userMapper.selectOne(any())).thenReturn(user);

        AuthService.LoginResult result = service.login(new AuthService.LoginRequest("carol", "right"));

        ArgumentCaptor<AuthSession> captor = ArgumentCaptor.forClass(AuthSession.class);
        verify(sessionMapper).insert(captor.capture());
        assertThat(captor.getValue().getTokenHash()).hasSize(64);
        assertThat(captor.getValue().getUserId()).isEqualTo(user.getId());
        assertThat(result.token()).isNotBlank();
        // token 明文不应等于库存哈希
        assertThat(result.token()).isNotEqualTo(captor.getValue().getTokenHash());
        verify(audit).record(any(), any(), any(), any(), any(), any(), any());
    }

    // ===== 校验链 =====

    private AuthSession session(LocalDateTime expiresAt) {
        AuthSession s = new AuthSession();
        s.setTokenHash("a".repeat(64));
        s.setUserId(42L);
        s.setExpiresAt(expiresAt);
        s.setCreatedAt(LocalDateTime.now());
        return s;
    }

    @Test
    void validate_未命中与过期返回null() {
        when(sessionMapper.selectById(any())).thenReturn(null);
        assertThat(service.validate("whatever")).isNull();

        when(sessionMapper.selectById(any())).thenReturn(session(LocalDateTime.now().minusMinutes(1)));
        assertThat(service.validate("whatever")).isNull();
    }

    @Test
    void validate_用户禁用返回null() {
        AuthUser disabled = activeUser("dave", "x");
        disabled.setStatus("DISABLED");
        when(sessionMapper.selectById(any())).thenReturn(session(LocalDateTime.now().plusDays(1)));
        when(userMapper.selectById(42L)).thenReturn(disabled);
        assertThat(service.validate("whatever")).isNull();
    }

    @Test
    void validate_命中返回WEB主体且寿命充裕时不续期() {
        when(sessionMapper.selectById(any())).thenReturn(session(LocalDateTime.now().plusDays(7).minusMinutes(5)));
        when(userMapper.selectById(42L)).thenReturn(activeUser("erin", "x"));

        var user = service.validate("whatever");

        assertThat(user).isNotNull();
        assertThat(user.username()).isEqualTo("erin");
        assertThat(user.channel().name()).isEqualTo("WEB");
        verify(sessionMapper, never()).updateById(any(AuthSession.class));
    }

    @Test
    void validate_寿命不足节流窗口时滑动续期() {
        when(sessionMapper.selectById(any())).thenReturn(session(LocalDateTime.now().plusHours(2)));
        when(userMapper.selectById(42L)).thenReturn(activeUser("frank", "x"));

        assertThat(service.validate("whatever")).isNotNull();

        ArgumentCaptor<AuthSession> captor = ArgumentCaptor.forClass(AuthSession.class);
        verify(sessionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getExpiresAt()).isAfter(LocalDateTime.now().plusDays(6));
    }

    // ===== 登出 =====

    @Test
    void logout_删除会话行() {
        String token = Base64.getUrlEncoder().encodeToString(new byte[16]);
        service.logout(token);
        verify(sessionMapper).deleteById(any(String.class));
    }
}
