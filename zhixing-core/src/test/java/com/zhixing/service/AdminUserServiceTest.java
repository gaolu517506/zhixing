package com.zhixing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.auth.AuthChannel;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.AuditEvent;
import com.zhixing.dao.entity.AuthUser;
import com.zhixing.dao.mapper.AuditEventMapper;
import com.zhixing.dao.mapper.AuthUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户管理单测（02 §B7 测试点）：非 ADMIN 403、禁用/启用审计接线、防自锁、幂等、
 * 重置密码、创建校验。
 */
class AdminUserServiceTest {

    private AuthUserMapper userMapper;

    private AuditEventMapper auditMapper;

    private AuditRecorder audit;

    private AdminUserService service;

    private static final CurrentUser ADMIN = new CurrentUser(1L, "root", "ADMIN", AuthChannel.WEB);

    private static final CurrentUser USER = new CurrentUser(2L, "member", "USER", AuthChannel.WEB);

    @BeforeEach
    void setUp() {
        // 纯 Mockito JVM 无 Spring 装配，LambdaQueryWrapper 需要 AuditEvent 的列缓存（生产态由 Mapper 注册时初始化）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                AuditEvent.class);
        userMapper = mock(AuthUserMapper.class);
        auditMapper = mock(AuditEventMapper.class);
        audit = mock(AuditRecorder.class);
        service = new AdminUserService(userMapper, auditMapper, audit, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private AuthUser user(long id, String username, String status) {
        AuthUser u = new AuthUser();
        u.setId(id);
        u.setUsername(username);
        u.setStatus(status);
        u.setSystemRole("USER");
        return u;
    }

    @Test
    void 非系统管理员全部拒绝() {
        AuthContext.set(USER);
        assertThatThrownBy(() -> service.list()).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create(new AdminUserService.CreateUserRequest("x", "pass-123", "USER")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.patch(9L, new AdminUserService.PatchUserRequest("DISABLE", null)))
                .isInstanceOf(BusinessException.class);
        verify(userMapper, never()).selectList(any());
    }

    @Test
    void 禁用接线USER_DISABLE审计_幂等不重复记() {
        AuthContext.set(ADMIN);
        when(userMapper.selectById(9L)).thenReturn(user(9L, "bob", "ACTIVE"));

        service.patch(9L, new AdminUserService.PatchUserRequest("DISABLE", null));
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(audit).record(any(), any(), action.capture(), any(), any(), any(), any());
        assertThat(action.getValue()).isEqualTo("USER_DISABLE");

        // 已禁用：幂等返回，不再落库/审计
        when(userMapper.selectById(9L)).thenReturn(user(9L, "bob", "DISABLED"));
        org.mockito.Mockito.clearInvocations(audit);
        service.patch(9L, new AdminUserService.PatchUserRequest("DISABLE", null));
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 启用接线USER_ENABLE审计() {
        AuthContext.set(ADMIN);
        when(userMapper.selectById(9L)).thenReturn(user(9L, "bob", "DISABLED"));

        service.patch(9L, new AdminUserService.PatchUserRequest("ENABLE", null));

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(audit).record(any(), any(), action.capture(), any(), any(), any(), any());
        assertThat(action.getValue()).isEqualTo("USER_ENABLE");
    }

    @Test
    void 不能禁用自己() {
        AuthContext.set(ADMIN);
        when(userMapper.selectById(1L)).thenReturn(user(1L, "root", "ACTIVE"));

        assertThatThrownBy(() -> service.patch(1L, new AdminUserService.PatchUserRequest("DISABLE", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能禁用");
        verify(userMapper, never()).updateById(any(AuthUser.class));
    }

    @Test
    void 重置密码落BCrypt哈希() {
        AuthContext.set(ADMIN);
        when(userMapper.selectById(9L)).thenReturn(user(9L, "bob", "ACTIVE"));

        service.patch(9L, new AdminUserService.PatchUserRequest("RESET_PASSWORD", "new-pass-99"));

        ArgumentCaptor<AuthUser> captor = ArgumentCaptor.forClass(AuthUser.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).startsWith("$2");
        assertThat(captor.getValue().getPasswordHash()).isNotEqualTo("new-pass-99");
    }

    @Test
    void 创建_非法角色与重复用户名拒绝() {
        AuthContext.set(ADMIN);
        when(userMapper.selectOne(any())).thenReturn(user(9L, "bob", "ACTIVE"));

        assertThatThrownBy(() -> service.create(new AdminUserService.CreateUserRequest("bob", "pass-123", "GUEST")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("systemRole");
        assertThatThrownBy(() -> service.create(new AdminUserService.CreateUserRequest("bob", "pass-123", "USER")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
        assertThatThrownBy(() -> service.create(new AdminUserService.CreateUserRequest("carol", "123", "USER")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("6~64");
    }

    @Test
    void 创建成功_密码BCrypt化() {
        AuthContext.set(ADMIN);
        when(userMapper.selectOne(any())).thenReturn(null);

        AdminUserService.UserView view =
                service.create(new AdminUserService.CreateUserRequest("carol", "pass-123", "ADMIN"));

        assertThat(view.username()).isEqualTo("carol");
        assertThat(view.systemRole()).isEqualTo("ADMIN");
        ArgumentCaptor<AuthUser> captor = ArgumentCaptor.forClass(AuthUser.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).startsWith("$2");
        assertThat(captor.getValue().getPasswordHash()).doesNotContain("pass-123");
    }

    @Test
    void list_lastLoginAt从LOGIN审计推导_取最近成功登录() {
        AuthContext.set(ADMIN);
        AuthUser bob = user(9L, "bob", "ACTIVE");
        bob.setCreatedAt(LocalDateTime.of(2026, 10, 1, 9, 0));
        AuthUser carol = user(10L, "carol", "ACTIVE");
        carol.setCreatedAt(LocalDateTime.of(2026, 10, 2, 9, 0));
        when(userMapper.selectList(any())).thenReturn(java.util.List.of(bob, carol));
        when(auditMapper.selectList(any())).thenReturn(java.util.List.of(
                loginEvent(9L, LocalDateTime.of(2026, 10, 28, 8, 0)),
                loginEvent(9L, LocalDateTime.of(2026, 10, 29, 8, 30)), // 更近一条应胜出
                loginEvent(10L, LocalDateTime.of(2026, 10, 20, 7, 0))));

        java.util.List<AdminUserService.UserView> views = service.list();

        assertThat(views).extracting(AdminUserService.UserView::username)
                .containsExactly("bob", "carol");
        assertThat(views.get(0).lastLoginAt()).isEqualTo(LocalDateTime.of(2026, 10, 29, 8, 30));
        assertThat(views.get(1).lastLoginAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 7, 0));
    }

    @Test
    void list_无LOGIN审计行_lastLoginAt为null_且mapper空返回不炸() {
        AuthContext.set(ADMIN);
        when(userMapper.selectList(any())).thenReturn(java.util.List.of(user(9L, "bob", "ACTIVE")));
        when(auditMapper.selectList(any())).thenReturn(null); // mock 未打桩的防御路径

        java.util.List<AdminUserService.UserView> views = service.list();

        assertThat(views).hasSize(1);
        assertThat(views.get(0).lastLoginAt()).isNull();
    }

    private AuditEvent loginEvent(long userId, LocalDateTime at) {
        AuditEvent event = new AuditEvent();
        event.setActorId(userId);
        event.setAction("LOGIN");
        event.setResult("OK");
        event.setCreatedAt(at);
        return event;
    }
}
