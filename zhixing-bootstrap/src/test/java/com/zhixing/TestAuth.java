package com.zhixing;

import cn.hutool.crypto.digest.BCrypt;
import cn.hutool.crypto.digest.DigestUtil;
import com.zhixing.dao.entity.AuthSession;
import com.zhixing.dao.entity.AuthUser;
import com.zhixing.dao.mapper.AuthSessionMapper;
import com.zhixing.dao.mapper.AuthUserMapper;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 测试身份助手（bootstrap MockMvc 集成测试共用）：直接经 mapper 播种用户 + 会话行，
 * 返回可用作 Authorization: Bearer 的明文 token。绕开 /api/auth/setup（其仅在空表可用，
 * 本地库已初始化）；登录端点自身的覆盖走 AuthFlowIntegrationTest。
 *
 * <p>口令在 JVM 内随机生成（本助手只铸 token 直连，不走向登录端点），口令哈希仅满足
 * password_hash 非空列——不落任何字面凭据。
 */
public final class TestAuth {

    private static volatile String cachedHash;

    private static final SecureRandom RANDOM = new SecureRandom();

    private TestAuth() {
    }

    public record SeededUser(long id, String username, String systemRole, String status, String token) {
    }

    public static SeededUser seed(AuthUserMapper userMapper, AuthSessionMapper sessionMapper,
                                  String username, String systemRole) {
        return seed(userMapper, sessionMapper, username, systemRole, "ACTIVE");
    }

    public static SeededUser seed(AuthUserMapper userMapper, AuthSessionMapper sessionMapper,
                                  String username, String systemRole, String status) {
        AuthUser user = new AuthUser();
        user.setId(cn.hutool.core.util.IdUtil.getSnowflakeNextId());
        user.setUsername(username);
        user.setPasswordHash(randomPasswordHash());
        user.setStatus(status);
        user.setSystemRole(systemRole);
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = null;
        if (sessionMapper != null) {
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            AuthSession session = new AuthSession();
            session.setTokenHash(DigestUtil.sha256Hex(token));
            session.setUserId(user.getId());
            session.setExpiresAt(LocalDateTime.now().plusDays(1));
            session.setCreatedAt(LocalDateTime.now());
            sessionMapper.insert(session);
        }
        return new SeededUser(user.getId(), username, systemRole, status, token);
    }

    public static String bearer(SeededUser user) {
        return "Bearer " + user.token();
    }

    public static void cleanup(AuthUserMapper userMapper, AuthSessionMapper sessionMapper, SeededUser user) {
        if (user == null) {
            return;
        }
        if (sessionMapper != null && user.token() != null) {
            sessionMapper.deleteById(DigestUtil.sha256Hex(user.token()));
        }
        userMapper.deleteById(user.id());
    }

    /** 随机口令哈希，JVM 内只算一次（盐内嵌于哈希，可复用到所有播种行）。 */
    private static String randomPasswordHash() {
        if (plainPassword == null) {
            synchronized (TestAuth.class) {
                if (plainPassword == null) {
                    byte[] bytes = new byte[24];
                    RANDOM.nextBytes(bytes);
                    plainPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                    cachedHash = BCrypt.hashpw(plainPassword);
                }
            }
        }
        return cachedHash;
    }

    /** 与 {@link #randomPasswordHash()} 配对的明文（JVM 随机生成，仅登录流程测试用）。 */
    public static String plaintextPassword() {
        randomPasswordHash();
        return plainPassword;
    }

    private static volatile String plainPassword;
}
