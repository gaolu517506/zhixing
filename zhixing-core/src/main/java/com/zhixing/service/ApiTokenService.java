package com.zhixing.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixing.common.AuthTokenException;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.AuthChannel;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.ApiToken;
import com.zhixing.dao.entity.AuthUser;
import com.zhixing.dao.mapper.ApiTokenMapper;
import com.zhixing.dao.mapper.AuthUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * 开放 API token 服务（v2.0 F5，02-技术方案 §B5，D16）：TokenResolver 策略实现 + 发放/吊销/列表。
 *
 * <p>解析链（线 A 的 AuthFilter 会话未命中后调用）：明文 SHA-256 查 api_token → ACTIVE 且未过期
 * → 原子消费当日配额 → 以 owner 身份 + API 渠道返回 {@link CurrentUser}——与前端同一权限模型，
 * PermissionService 只消费 id/systemRole，渠道差异不影响权限判定。
 * 未命中/已吊销/已过期/归属用户被禁用返回 null（Filter 回 401）；命中但配额耗尽抛
 * {@link AuthTokenException}(A0007)——命中与超额是两种语义，Filter 分别翻译。
 *
 * <p>明文 {@code zxk_<32B random>}（Base64URL，43 字符）仅发放时返回一次，库存 SHA-256。
 */
@Slf4j
@Service
public class ApiTokenService implements TokenResolver {

    private static final String TOKEN_PREFIX = "zxk_";

    private static final String STATUS_ACTIVE = "ACTIVE";

    private static final String STATUS_REVOKED = "REVOKED";

    private static final String STATUS_DISABLED = "DISABLED";

    private static final int DEFAULT_DAILY_QUOTA = 1000;

    private final ApiTokenMapper tokenMapper;

    private final AuthUserMapper userMapper;

    private final AuditRecorder auditRecorder;

    public ApiTokenService(ApiTokenMapper tokenMapper, AuthUserMapper userMapper, AuditRecorder auditRecorder) {
        this.tokenMapper = tokenMapper;
        this.userMapper = userMapper;
        this.auditRecorder = auditRecorder;
    }

    @Override
    public CurrentUser resolve(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(TOKEN_PREFIX)) {
            return null;
        }
        ApiToken token = tokenMapper.selectOne(new LambdaQueryWrapper<ApiToken>()
                .eq(ApiToken::getTokenHash, DigestUtil.sha256Hex(rawToken)));
        if (token == null || !STATUS_ACTIVE.equals(token.getStatus())
                || (token.getExpiresAt() != null && token.getExpiresAt().isBefore(LocalDateTime.now()))) {
            return null;
        }
        // 原子消费配额：0 行 = 超额，或读后被并发吊销（重读分辨，绝不让吊销被误报成超额）
        if (tokenMapper.consumeQuota(token.getId(), LocalDate.now()) == 0) {
            ApiToken latest = tokenMapper.selectById(token.getId());
            if (latest == null || !STATUS_ACTIVE.equals(latest.getStatus())) {
                return null;
            }
            throw new AuthTokenException(ErrorCode.QUOTA_EXCEEDED);
        }
        AuthUser owner = userMapper.selectById(token.getOwnerUserId());
        if (owner == null || STATUS_DISABLED.equals(owner.getStatus())) {
            return null; // 归属用户被禁用/删除：未命中语义（401），不算超额
        }
        return new CurrentUser(owner.getId(), owner.getUsername(), owner.getSystemRole(), AuthChannel.API);
    }

    /** 发放：明文只在返回值里出现一次；owner 缺省为调用者本人（系统 ADMIN 可代发）。 */
    public IssuedToken issue(String name, Long ownerUserId, Integer dailyQuota, LocalDateTime expiresAt) {
        CurrentUser caller = requireAdmin();
        long ownerId = ownerUserId != null ? ownerUserId : caller.id();
        AuthUser owner = userMapper.selectById(ownerId);
        if (owner == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "归属用户不存在：" + ownerId);
        }
        long id = IdUtil.getSnowflakeNextId();
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String plaintext = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        LocalDateTime now = LocalDateTime.now();
        ApiToken token = new ApiToken();
        token.setId(id);
        token.setName(name);
        token.setTokenHash(DigestUtil.sha256Hex(plaintext));
        token.setOwnerUserId(ownerId);
        token.setStatus(STATUS_ACTIVE);
        token.setDailyQuota(dailyQuota != null ? dailyQuota : DEFAULT_DAILY_QUOTA);
        token.setUsedToday(0);
        token.setUsedDate(now.toLocalDate());
        token.setExpiresAt(expiresAt);
        token.setCreatedAt(now);
        tokenMapper.insert(token);
        // 审计失败不影响主链路（AuditRecorder 契约：实现自吞异常），此处直接调用
        auditRecorder.record(caller.id(), caller.username(), "TOKEN_ISSUE", "TOKEN", Long.toString(id),
                "OK", "{\"ownerUserId\":" + ownerId + ",\"dailyQuota\":" + token.getDailyQuota() + "}");
        return new IssuedToken(id, name, plaintext, ownerId, token.getDailyQuota(), expiresAt);
    }

    /** 吊销即失效（下次 resolve 选中 REVOKED → 未命中）；重复吊销幂等成功。 */
    public void revoke(long tokenId) {
        CurrentUser caller = requireAdmin();
        ApiToken token = tokenMapper.selectById(tokenId);
        if (token == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "token 不存在：" + tokenId);
        }
        if (STATUS_REVOKED.equals(token.getStatus())) {
            return;
        }
        ApiToken patch = new ApiToken();
        patch.setId(tokenId);
        patch.setStatus(STATUS_REVOKED);
        tokenMapper.updateById(patch);
        auditRecorder.record(caller.id(), caller.username(), "TOKEN_REVOKE", "TOKEN", Long.toString(tokenId), "OK", null);
    }

    /** 列表（脱哈希在 controller 响应层完成，service 返回实体不外传）。 */
    public List<ApiToken> list() {
        requireAdmin();
        return tokenMapper.selectList(new LambdaQueryWrapper<ApiToken>().orderByDesc(ApiToken::getCreatedAt));
    }

    /** /api/admin/* 的路径级门控由线 A 的 AuthFilter 承接；此处为纵深防御（Filter 合入前唯一闸口）。 */
    private CurrentUser requireAdmin() {
        CurrentUser caller = AuthContext.current();
        if (!caller.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return caller;
    }

    /** 发放结果：plaintextToken 仅此一次，调用方落库/展示后即丢。 */
    public record IssuedToken(long id, String name, String plaintextToken, long ownerUserId,
                              int dailyQuota, LocalDateTime expiresAt) {
    }
}
