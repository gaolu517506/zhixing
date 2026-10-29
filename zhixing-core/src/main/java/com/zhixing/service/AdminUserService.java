package com.zhixing.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.AuthUser;
import com.zhixing.dao.mapper.AuthUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户管理（02-技术方案 §B7，F7）：仅系统 ADMIN（门禁在 service 入口，controller 零判断）。
 * 禁用/启用接线 USER_DISABLE/USER_ENABLE 审计；禁用即失效语义由线 A 校验链承接（查 status）。
 *
 * <p>防自锁：不允许禁用自己（否则唯一的系统管理员可能把整个后台锁死）。
 * 重置密码不在 14 类审计事件枚举内（02 §B3 闭合集合），不落审计事件——是否扩展枚举由 lead 裁决。
 */
@Slf4j
@Service
public class AdminUserService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private static final String STATUS_DISABLED = "DISABLED";

    private static final String ROLE_ADMIN = "ADMIN";

    private static final String ROLE_USER = "USER";

    private final AuthUserMapper userMapper;

    private final AuditRecorder audit;

    private final ObjectMapper objectMapper;

    public AdminUserService(AuthUserMapper userMapper, AuditRecorder audit, ObjectMapper objectMapper) {
        this.userMapper = userMapper;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public record UserView(Long id, String username, String status, String systemRole,
                           LocalDateTime createdAt) {

        static UserView of(AuthUser user) {
            return new UserView(user.getId(), user.getUsername(), user.getStatus(),
                    user.getSystemRole(), user.getCreatedAt());
        }
    }

    public record CreateUserRequest(String username, String password, String systemRole) {
    }

    public record PatchUserRequest(String action, String newPassword) {
    }

    public List<UserView> list() {
        requireSystemAdmin();
        return userMapper.selectList(new LambdaQueryWrapper<AuthUser>()
                        .orderByDesc(AuthUser::getCreatedAt)
                        .orderByDesc(AuthUser::getId))
                .stream().map(UserView::of).toList();
    }

    public UserView create(CreateUserRequest request) {
        CurrentUser caller = requireSystemAdmin();
        String username = request.username() == null ? "" : request.username().trim();
        String password = request.password() == null ? "" : request.password();
        String role = request.systemRole() == null ? ROLE_USER : request.systemRole().trim();
        if (username.isEmpty() || username.length() > 64) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "username 不能为空且不超过 64 字符");
        }
        if (password.length() < 6 || password.length() > 64) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "password 长度须在 6~64 之间");
        }
        if (!ROLE_ADMIN.equals(role) && !ROLE_USER.equals(role)) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "systemRole 仅支持 USER / ADMIN");
        }
        if (findByName(username) != null) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "用户名已存在：" + username);
        }
        AuthUser user = new AuthUser();
        user.setId(IdUtil.getSnowflakeNextId());
        user.setUsername(username);
        user.setPasswordHash(BCrypt.hashpw(password));
        user.setStatus(STATUS_ACTIVE);
        user.setSystemRole(role);
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);
        log.info("管理员 {} 创建用户 {}（{}）", caller.username(), username, role);
        return UserView.of(user);
    }

    /** action：DISABLE / ENABLE / RESET_PASSWORD（action 与字段校验在 service 收口）。 */
    public UserView patch(long userId, PatchUserRequest request) {
        CurrentUser caller = requireSystemAdmin();
        AuthUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在：" + userId);
        }
        String action = request.action() == null ? "" : request.action().trim().toUpperCase();
        AuthUser patch = new AuthUser();
        patch.setId(userId);
        switch (action) {
            case "DISABLE" -> {
                if (caller.id().equals(userId)) {
                    throw new BusinessException(ErrorCode.CLIENT_ERROR, "不能禁用当前登录账号自己");
                }
                if (STATUS_DISABLED.equals(user.getStatus())) {
                    return UserView.of(user); // 幂等
                }
                patch.setStatus(STATUS_DISABLED);
                userMapper.updateById(patch);
                String detailJson = targetDetailJson(user.getUsername());
                audit.record(caller.id(), caller.username(), AuditAction.USER_DISABLE.name(),
                        "USER", String.valueOf(userId), AuditResult.OK, detailJson);
                log.info("管理员 {} 禁用用户 {}", caller.username(), user.getUsername());
            }
            case "ENABLE" -> {
                if (STATUS_ACTIVE.equals(user.getStatus())) {
                    return UserView.of(user); // 幂等
                }
                patch.setStatus(STATUS_ACTIVE);
                userMapper.updateById(patch);
                String detailJson = targetDetailJson(user.getUsername());
                audit.record(caller.id(), caller.username(), AuditAction.USER_ENABLE.name(),
                        "USER", String.valueOf(userId), AuditResult.OK, detailJson);
                log.info("管理员 {} 启用用户 {}", caller.username(), user.getUsername());
            }
            case "RESET_PASSWORD" -> {
                String newPassword = request.newPassword() == null ? "" : request.newPassword();
                if (newPassword.length() < 6 || newPassword.length() > 64) {
                    throw new BusinessException(ErrorCode.CLIENT_ERROR, "newPassword 长度须在 6~64 之间");
                }
                patch.setPasswordHash(BCrypt.hashpw(newPassword));
                userMapper.updateById(patch);
                log.info("管理员 {} 重置用户 {} 的密码", caller.username(), user.getUsername());
            }
            default -> throw new BusinessException(ErrorCode.CLIENT_ERROR,
                    "action 仅支持 DISABLE / ENABLE / RESET_PASSWORD");
        }
        return UserView.of(userMapper.selectById(userId));
    }

    private CurrentUser requireSystemAdmin() {
        CurrentUser caller = AuthContext.current();
        if (!caller.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅系统管理员可管理用户");
        }
        return caller;
    }

    private AuthUser findByName(String username) {
        // auth_user.username 唯一索引保证单行
        return userMapper.selectOne(new LambdaQueryWrapper<AuthUser>()
                .eq(AuthUser::getUsername, username));
    }

    /** 审计 detail 安全序列化（用户名等可控字符串不过手拼）；失败降级 null 不反噬业务。 */
    private String targetDetailJson(String targetUsername) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("target", targetUsername);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }
}
