package com.zhixing.service;

import com.zhixing.common.AuthTokenException;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.auth.AuthChannel;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.ApiToken;
import com.zhixing.dao.entity.AuthUser;
import com.zhixing.dao.mapper.ApiTokenMapper;
import com.zhixing.dao.mapper.AuthUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * token 全生命周期测试（02-技术方案 §B5，任务书 §3）：发放→调用→配额→吊销即失效，
 * 外加 token 与会话权限一致性断言（同 owner 同 id 同角色，仅渠道不同——PermissionService
 * 只消费 id/systemRole，D16 同一权限模型）。全部桩化离线。
 */
class ApiTokenServiceTest {

    private static final long ADMIN_ID = 1L;
    private static final long OWNER_ID = 100L;

    private ApiTokenMapper tokenMapper;

    private AuthUserMapper userMapper;

    private AuditRecorder auditRecorder;

    private ApiTokenService service;

    @BeforeEach
    void setUp() {
        tokenMapper = mock(ApiTokenMapper.class);
        userMapper = mock(AuthUserMapper.class);
        auditRecorder = mock(AuditRecorder.class);
        service = new ApiTokenService(tokenMapper, userMapper, auditRecorder);
        // 调用者：系统 ADMIN（线 A AuthFilter 合入前由测试直接置上下文）
        AuthContext.set(new CurrentUser(ADMIN_ID, "admin", "ADMIN", AuthChannel.WEB));
        AuthUser owner = new AuthUser();
        owner.setId(OWNER_ID);
        owner.setUsername("dev");
        owner.setStatus("ACTIVE");
        owner.setSystemRole("USER");
        when(userMapper.selectById(OWNER_ID)).thenReturn(owner);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private ApiToken storedToken() {
        ApiToken token = new ApiToken();
        token.setId(900L);
        token.setName("开放 API");
        token.setTokenHash("a".repeat(64));
        token.setOwnerUserId(OWNER_ID);
        token.setStatus("ACTIVE");
        token.setDailyQuota(1000);
        token.setUsedToday(0);
        token.setUsedDate(LocalDate.now());
        return token;
    }

    // ===== 发放 =====

    @Test
    void 发放落哈希不落明文_明文仅返回一次_审计TOKEN_ISSUE() {
        when(tokenMapper.insert(any(ApiToken.class))).thenReturn(1);

        ApiTokenService.IssuedToken issued = service.issue("开放 API", OWNER_ID, null, null);

        assertThat(issued.plaintextToken()).startsWith("zxk_");
        assertThat(issued.plaintextToken()).hasSize("zxk_".length() + 43); // 32B → Base64URL 无填充 43 字符
        assertThat(issued.dailyQuota()).isEqualTo(1000); // 缺省配额
        assertThat(issued.ownerUserId()).isEqualTo(OWNER_ID);

        ArgumentCaptor<ApiToken> captor = ArgumentCaptor.forClass(ApiToken.class);
        verify(tokenMapper).insert(captor.capture());
        ApiToken saved = captor.getValue();
        assertThat(saved.getTokenHash()).hasSize(64).doesNotContain("zxk_"); // 库存哈希，不含明文
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getUsedToday()).isZero();
        assertThat(saved.getUsedDate()).isEqualTo(LocalDate.now());

        verify(auditRecorder).record(eq(ADMIN_ID), eq("admin"), eq("TOKEN_ISSUE"),
                eq("TOKEN"), eq(Long.toString(issued.id())), eq("OK"), any());
    }

    @Test
    void 发放未知归属用户报NOT_FOUND() {
        assertThatThrownBy(() -> service.issue("x", 999L, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        verify(tokenMapper, never()).insert(any(ApiToken.class));
    }

    @Test
    void 非管理员发放被拒() {
        AuthContext.set(new CurrentUser(OWNER_ID, "dev", "USER", AuthChannel.WEB));
        assertThatThrownBy(() -> service.issue("x", null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ===== 调用（TokenResolver）=====

    @Test
    void resolve命中以owner身份API渠道返回() {
        ApiToken token = storedToken();
        when(tokenMapper.selectOne(any())).thenReturn(token);
        when(tokenMapper.consumeQuota(eq(900L), any(LocalDate.class))).thenReturn(1);

        CurrentUser user = service.resolve("zxk_valid");

        assertThat(user.id()).isEqualTo(OWNER_ID);
        assertThat(user.username()).isEqualTo("dev");
        assertThat(user.systemRole()).isEqualTo("USER");
        assertThat(user.channel()).isEqualTo(AuthChannel.API);
    }

    /** D16 一致性：token 解析出的主体与同 owner 的 Web 会话主体同 id 同角色（渠道除外）——同一权限模型。 */
    @Test
    void resolve与同owner会话主体权限一致性() {
        ApiToken token = storedToken();
        when(tokenMapper.selectOne(any())).thenReturn(token);
        when(tokenMapper.consumeQuota(eq(900L), any(LocalDate.class))).thenReturn(1);

        CurrentUser fromToken = service.resolve("zxk_valid");
        CurrentUser fromWebSession = new CurrentUser(OWNER_ID, "dev", "USER", AuthChannel.WEB);

        assertThat(fromToken.id()).isEqualTo(fromWebSession.id());
        assertThat(fromToken.systemRole()).isEqualTo(fromWebSession.systemRole());
        assertThat(fromToken.channel()).isNotEqualTo(fromWebSession.channel());
    }

    @Test
    void resolve形态不符或未命中返回null() {
        assertThat(service.resolve(null)).isNull();
        assertThat(service.resolve("Bearer session-opaque")).isNull(); // 非 zxk_ 前缀：会话渠道的事
        when(tokenMapper.selectOne(any())).thenReturn(null);
        assertThat(service.resolve("zxk_unknown")).isNull();
    }

    @Test
    void resolve已吊销或已过期返回null() {
        ApiToken revoked = storedToken();
        revoked.setStatus("REVOKED");
        when(tokenMapper.selectOne(any())).thenReturn(revoked);
        assertThat(service.resolve("zxk_revoked")).isNull();

        ApiToken expired = storedToken();
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(tokenMapper.selectOne(any())).thenReturn(expired);
        assertThat(service.resolve("zxk_expired")).isNull();
        verify(tokenMapper, never()).consumeQuota(anyLong(), any());
    }

    @Test
    void resolve配额耗尽抛A0007且不查归属用户() {
        ApiToken token = storedToken();
        when(tokenMapper.selectOne(any())).thenReturn(token);
        when(tokenMapper.consumeQuota(eq(900L), any(LocalDate.class))).thenReturn(0); // 当天已用尽
        when(tokenMapper.selectById(900L)).thenReturn(token);

        assertThatThrownBy(() -> service.resolve("zxk_exhausted"))
                .isInstanceOf(AuthTokenException.class)
                .extracting(e -> ((AuthTokenException) e).errorCode().code())
                .isEqualTo(ErrorCode.QUOTA_EXCEEDED.code());
        verify(userMapper, never()).selectById(any());
    }

    @Test
    void resolve读后吊销窗口按未命中处理而非超额() {
        ApiToken token = storedToken();
        when(tokenMapper.selectOne(any())).thenReturn(token);
        when(tokenMapper.consumeQuota(eq(900L), any(LocalDate.class))).thenReturn(0);
        ApiToken latest = storedToken();
        latest.setStatus("REVOKED");
        when(tokenMapper.selectById(900L)).thenReturn(latest);

        assertThat(service.resolve("zxk_revoked-concurrently")).isNull();
    }

    @Test
    void resolve归属用户被禁用返回null() {
        ApiToken token = storedToken();
        when(tokenMapper.selectOne(any())).thenReturn(token);
        when(tokenMapper.consumeQuota(eq(900L), any(LocalDate.class))).thenReturn(1);
        AuthUser disabled = new AuthUser();
        disabled.setId(OWNER_ID);
        disabled.setUsername("dev");
        disabled.setStatus("DISABLED");
        disabled.setSystemRole("USER");
        when(userMapper.selectById(OWNER_ID)).thenReturn(disabled);

        assertThat(service.resolve("zxk_owner-disabled")).isNull();
    }

    // ===== 吊销 / 列表 =====

    @Test
    void 吊销即失效并审计TOKEN_REVOKE() {
        ApiToken token = storedToken();
        when(tokenMapper.selectById(900L)).thenReturn(token);

        service.revoke(900L);

        ArgumentCaptor<ApiToken> captor = ArgumentCaptor.forClass(ApiToken.class);
        verify(tokenMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("REVOKED");
        verify(auditRecorder).record(eq(ADMIN_ID), eq("admin"), eq("TOKEN_REVOKE"),
                eq("TOKEN"), eq("900"), eq("OK"), any());

        // 吊销后下次 resolve：REVOKED 未命中
        when(tokenMapper.selectOne(any())).thenReturn(captor.getValue());
        assertThat(service.resolve("zxk_valid")).isNull();
    }

    @Test
    void 吊销幂等_重复吊销不再审计() {
        ApiToken revoked = storedToken();
        revoked.setStatus("REVOKED");
        when(tokenMapper.selectById(900L)).thenReturn(revoked);

        assertThatCode(() -> service.revoke(900L)).doesNotThrowAnyException();
        verify(tokenMapper, never()).updateById(any(ApiToken.class));
        verify(auditRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 吊销不存在报NOT_FOUND() {
        when(tokenMapper.selectById(900L)).thenReturn(null);
        assertThatThrownBy(() -> service.revoke(900L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }
}
