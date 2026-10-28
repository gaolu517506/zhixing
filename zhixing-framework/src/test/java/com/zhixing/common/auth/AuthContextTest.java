package com.zhixing.common.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AuthContext 生命周期契约（T6-0）：set/clear 对称、未认证抛状态异常、
 * 线程池复用下 clear 后不残留（泄漏即越权，Filter 必须 finally 清除）。
 */
class AuthContextTest {

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    @DisplayName("set 后 current 返回同一主体，clear 后不残留")
    void setAndClear() {
        CurrentUser alice = new CurrentUser(1L, "alice", "USER", AuthChannel.WEB);
        AuthContext.set(alice);
        assertThat(AuthContext.current()).isSameAs(alice);
        AuthContext.clear();
        assertThat(AuthContext.currentOrNull()).isNull();
    }

    @Test
    @DisplayName("未认证时 current 抛 IllegalStateException，currentOrNull 返回 null")
    void unauthenticated() {
        assertThat(AuthContext.currentOrNull()).isNull();
        assertThatThrownBy(AuthContext::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未认证请求不应到达业务层");
    }

    @Test
    @DisplayName("isAdmin 仅 ADMIN 为真；渠道无关")
    void adminRole() {
        assertThat(new CurrentUser(1L, "a", "ADMIN", AuthChannel.API).isAdmin()).isTrue();
        assertThat(new CurrentUser(2L, "b", "USER", AuthChannel.WEB).isAdmin()).isFalse();
    }
}
