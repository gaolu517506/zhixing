package com.zhixing.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.auth.AuthChannel;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.config.AuthProperties;
import com.zhixing.dao.entity.AuthSession;
import com.zhixing.dao.entity.AuthUser;
import com.zhixing.dao.mapper.AuthSessionMapper;
import com.zhixing.dao.mapper.AuthUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.validation.constraints.NotBlank;

/**
 * 认证应用服务（02-技术方案 §B1）：登录（BCrypt + opaque token）/ 登出 / 首启 setup /
 * token 校验链（AuthFilter 每请求调用）。
 *
 * <p>token 语义（D13）：明文 32 字节随机 base64url 仅登录响应出现一次，库存 SHA-256——
 * 泄库不泄凭据；登出删 session 行即吊销；用户禁用（status=DISABLED）后存量会话在下一次
 * 校验链即失效。7 天有效期、活跃滑动续期（节流：剩余寿命不足 {@code renewWindowHours}
 * 才续，活跃会话至多每天一次 UPDATE）。
 *
 * <p>防枚举：用户不存在与密码错误统一「账号或密码错误」；禁用账号同样文案（不泄露账号状态）。
 * 连续失败 {@code maxFailedAttempts} 次锁定 {@code lockMinutes} 分钟——单实例内存计数
 * （§B1 规格即如此，重启清零；多实例部署时属线外演进）。
 */
@Slf4j
@Service
public class AuthService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private static final String ROLE_ADMIN = "ADMIN";

    private static final int TOKEN_BYTES = 32;

    private final AuthUserMapper userMapper;

    private final AuthSessionMapper sessionMapper;

    private final AuditRecorder audit;

    private final AuthProperties props;

    private final SecureRandom random = new SecureRandom();

    /** 连续失败计数（username → 状态）。登录成功移除；锁到期自然失效（惰性清理）。 */
    private final Map<String, FailState> failures = new ConcurrentHashMap<>();

    /** setup 的「查空 + 建首号」临界区锁（单实例；并发首启由 username 唯一索引兜底）。 */
    private final Object setupLock = new Object();

    public AuthService(AuthUserMapper userMapper, AuthSessionMapper sessionMapper,
                       AuditRecorder audit, AuthProperties props) {
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.audit = audit;
        this.props = props;
    }

    // ===== 请求/响应载体 =====

    public record LoginRequest(@NotBlank(message = "username 不能为空") String username,
                               @NotBlank(message = "password 不能为空") String password) {
    }

    public record SetupRequest(@NotBlank(message = "username 不能为空") String username,
                               @NotBlank(message = "password 不能为空") String password) {
    }

    /** 用户视图（登录/setup/me 共用）。 */
    public record UserView(Long id, String username, String systemRole) {

        public static UserView of(AuthUser user) {
            return new UserView(user.getId(), user.getUsername(), user.getSystemRole());
        }
    }

    /** 登录/setup 成功结果：token 明文仅此一次返回。 */
    public record LoginResult(String token, UserView user) {
    }

    // ===== 登录 / 登出 =====

    public LoginResult login(LoginRequest req) {
        String username = req.username().trim();
        requireNotLocked(username);

        AuthUser user = findByName(username);
        boolean passwordOk = user != null && checkPassword(req.password(), user.getPasswordHash());
        if (!passwordOk || !STATUS_ACTIVE.equals(user.getStatus())) {
            onLoginFailure(username, user != null && !STATUS_ACTIVE.equals(user.getStatus()));
            // 防枚举：账号不存在 / 密码错误 / 账号禁用，三种失败统一文案
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }

        failures.remove(username);
        String token = issueSession(user);
        audit.record(user.getId(), user.getUsername(), AuditAction.LOGIN.name(),
                "USER", String.valueOf(user.getId()), AuditResult.OK, null);
        log.info("登录成功 username={} userId={}", username, user.getId());
        return new LoginResult(token, UserView.of(user));
    }

    public void logout(String rawToken) {
        sessionMapper.deleteById(DigestUtil.sha256Hex(rawToken));
        CurrentUser actor = AuthContext.currentOrNull();
        audit.record(actor == null ? null : actor.id(), actor == null ? null : actor.username(),
                AuditAction.LOGOUT.name(), null, null, AuditResult.OK, null);
    }

    // ===== 首启 setup（02 §B1：仅空表可用，创建 ADMIN） =====

    public boolean needSetup() {
        return userMapper.selectCount(null) == 0;
    }

    public LoginResult setup(SetupRequest req) {
        String username = req.username().trim();
        AuthUser user;
        synchronized (setupLock) {
            if (userMapper.selectCount(null) > 0) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "系统已初始化，不可重复执行");
            }
            user = new AuthUser();
            user.setId(IdUtil.getSnowflakeNextId());
            user.setUsername(username);
            user.setPasswordHash(BCrypt.hashpw(req.password()));
            user.setStatus(STATUS_ACTIVE);
            user.setSystemRole(ROLE_ADMIN);
            user.setCreatedAt(LocalDateTime.now());
            userMapper.insert(user);
        }
        log.info("首启初始化：创建系统管理员 username={} userId={}", username, user.getId());
        return new LoginResult(issueSession(user), UserView.of(user));
    }

    // ===== 校验链（AuthFilter 每请求调用；02 §B1） =====

    /**
     * 明文 token → 会话（未过期）→ 用户（ACTIVE）。任一环不满足返回 null（Filter 决定 401）。
     * 命中且剩余寿命不足 renewWindowHours 时滑动续期。
     */
    public CurrentUser validate(String rawToken) {
        AuthSession session = sessionMapper.selectById(DigestUtil.sha256Hex(rawToken));
        if (session == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (session.getExpiresAt().isBefore(now)) {
            return null;
        }
        AuthUser user = userMapper.selectById(session.getUserId());
        if (user == null || !STATUS_ACTIVE.equals(user.getStatus())) {
            return null;
        }
        slideIfDue(session, now);
        return new CurrentUser(user.getId(), user.getUsername(), user.getSystemRole(), AuthChannel.WEB);
    }

    /** 滑动续期（节流）：剩余寿命 < TTL - renewWindow 才续期。失败仅日志——续期失败不该拒绝一个已验证的请求。 */
    private void slideIfDue(AuthSession session, LocalDateTime now) {
        LocalDateTime renewDueBefore = now.plusDays(props.getSessionTtlDays())
                .minusHours(props.getRenewWindowHours());
        if (!session.getExpiresAt().isBefore(renewDueBefore)) {
            return;
        }
        try {
            AuthSession patch = new AuthSession();
            patch.setTokenHash(session.getTokenHash());
            patch.setExpiresAt(now.plusDays(props.getSessionTtlDays()));
            sessionMapper.updateById(patch);
        } catch (Exception e) {
            log.warn("会话滑动续期失败 tokenHash={}…: {}", session.getTokenHash().substring(0, 8), e.getMessage());
        }
    }

    // ===== 内部 =====

    private void requireNotLocked(String username) {
        FailState state = failures.get(username);
        if (state != null && state.lockedUntilMillis > System.currentTimeMillis()) {
            long remainSeconds = (state.lockedUntilMillis - System.currentTimeMillis()) / 1000 + 1;
            throw new BusinessException(ErrorCode.UNAUTHORIZED,
                    "失败次数过多，账号已锁定，请约 " + Math.max(remainSeconds / 60, 1) + " 分钟后再试");
        }
    }

    private void onLoginFailure(String username, boolean disabled) {
        int max = props.getMaxFailedAttempts();
        FailState state = failures.compute(username, (k, prev) -> {
            long now = System.currentTimeMillis();
            // 锁未设置（0）或已过期 → 从 1 重新计；锁定中保持计数（锁定到期前反正进不来）
            boolean freshWindow = prev == null || (prev.lockedUntilMillis > 0 && now > prev.lockedUntilMillis);
            int count = freshWindow ? 1 : prev.count + 1;
            long lockedUntil = count >= max ? now + props.getLockMinutes() * 60_000L : 0;
            return new FailState(count, lockedUntil);
        });
        String detail = disabled ? "{\"reason\":\"DISABLED\"}"
                : (state.lockedUntilMillis > 0 ? "{\"reason\":\"LOCKED_NOW\"}" : "{\"reason\":\"BAD_CREDENTIALS\"}");
        audit.record(null, username, AuditAction.LOGIN.name(), "USER", username,
                AuditResult.FAIL, detail);
    }

    private AuthUser findByName(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<AuthUser>()
                .eq(AuthUser::getUsername, username)
                .last("LIMIT 1"));
    }

    private boolean checkPassword(String rawPassword, String passwordHash) {
        try {
            return BCrypt.checkpw(rawPassword, passwordHash);
        } catch (Exception e) {
            // 库中哈希形态异常（如历史脏数据）按校验失败处理，不向攻击者泄露差异
            return false;
        }
    }

    /** 签发会话：32 字节随机 base64url 明文 + SHA-256 落库（明文只在返回值出现一次）。 */
    private String issueSession(AuthUser user) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        AuthSession session = new AuthSession();
        session.setTokenHash(DigestUtil.sha256Hex(token));
        session.setUserId(user.getId());
        session.setExpiresAt(LocalDateTime.now().plusDays(props.getSessionTtlDays()));
        session.setCreatedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        return token;
    }

    private record FailState(int count, long lockedUntilMillis) {
    }
}
