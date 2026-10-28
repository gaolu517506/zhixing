package com.zhixing.controller;

import com.zhixing.common.auth.AuthChannel;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.ApiToken;
import com.zhixing.service.ApiTokenService;
import com.zhixing.service.ApiTokenService.IssuedToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * token 管理端点测试（v2.0 F5）：发放响应含一次性明文、列表脱哈希（响应记录无 tokenHash 分量）。
 * service 生命周期已在 core 的 ApiTokenServiceTest 覆盖，此处只测响应整形。
 */
class ApiTokenAdminControllerTest {

    private ApiTokenService tokenService;

    private ApiTokenAdminController controller;

    @BeforeEach
    void setUp() {
        tokenService = mock(ApiTokenService.class);
        controller = new ApiTokenAdminController(tokenService);
        AuthContext.set(new CurrentUser(1L, "admin", "ADMIN", AuthChannel.WEB));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void 发放响应携带一次性明文与元数据() {
        when(tokenService.issue("开放 API", 100L, 500, null))
                .thenReturn(new IssuedToken(900L, "开放 API", "zxk_abc123", 100L, 500, null));

        var body = controller.issue(new ApiTokenAdminController.CreateTokenRequest("开放 API", 100L, 500, null)).getData();

        assertThat(body.token()).startsWith("zxk_");
        assertThat(body.id()).isEqualTo(900L);
        assertThat(body.ownerUserId()).isEqualTo(100L);
        assertThat(body.dailyQuota()).isEqualTo(500);
    }

    @Test
    void 列表脱哈希_响应记录无tokenHash分量() {
        ApiToken token = new ApiToken();
        token.setId(900L);
        token.setName("开放 API");
        token.setTokenHash("a".repeat(64));
        token.setOwnerUserId(100L);
        token.setStatus("ACTIVE");
        token.setDailyQuota(1000);
        token.setUsedToday(3);
        token.setUsedDate(LocalDate.now());
        when(tokenService.list()).thenReturn(List.of(token));

        var body = controller.list().getData();

        assertThat(body).hasSize(1);
        // 脱哈希核心断言：响应记录分量里不存在 tokenHash——类型层面杜绝泄漏通道
        assertThat(body.get(0).getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("tokenHash");
        assertThat(body.get(0).usedToday()).isEqualTo(3);
    }

    @Test
    void 吊销端点透传service() {
        var response = controller.revoke(900L);
        org.mockito.Mockito.verify(tokenService).revoke(eq(900L));
        assertThat(response.getCode()).isEqualTo("0");
    }
}
