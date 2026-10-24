package com.zhixing.infra.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误分类的契约测试：这是阶段 4 降级决策的输入，分错一类，降级就切错方向。
 */
class ErrorTypeTest {

    @Test
    void http状态码分类() {
        assertThat(ErrorType.fromHttpStatus(401)).isEqualTo(ErrorType.UNAUTHORIZED);
        assertThat(ErrorType.fromHttpStatus(403)).isEqualTo(ErrorType.UNAUTHORIZED);
        assertThat(ErrorType.fromHttpStatus(429)).isEqualTo(ErrorType.RATE_LIMITED);
        assertThat(ErrorType.fromHttpStatus(500)).isEqualTo(ErrorType.SERVER_ERROR);
        assertThat(ErrorType.fromHttpStatus(503)).isEqualTo(ErrorType.SERVER_ERROR);
        assertThat(ErrorType.fromHttpStatus(400)).isEqualTo(ErrorType.CLIENT_ERROR);
        assertThat(ErrorType.fromHttpStatus(422)).isEqualTo(ErrorType.CLIENT_ERROR);
        assertThat(ErrorType.fromHttpStatus(200)).isEqualTo(ErrorType.INVALID_RESPONSE);
    }

    @Test
    void 可降级错误恰好是网络服务端超时三类() {
        assertThat(ErrorType.NETWORK_ERROR.isFallbackCapable()).isTrue();
        assertThat(ErrorType.SERVER_ERROR.isFallbackCapable()).isTrue();
        assertThat(ErrorType.TIMEOUT.isFallbackCapable()).isTrue();

        assertThat(ErrorType.UNAUTHORIZED.isFallbackCapable()).isFalse();
        assertThat(ErrorType.RATE_LIMITED.isFallbackCapable()).isFalse();
        assertThat(ErrorType.CLIENT_ERROR.isFallbackCapable()).isFalse();
        assertThat(ErrorType.INVALID_RESPONSE.isFallbackCapable()).isFalse();
    }
}
