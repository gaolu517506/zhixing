package com.zhixing.infra.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * embedding 备胎装饰器行为测试（任务书 v1.1-B §4，全部假客户端离线）：
 * 429 切备 / 401 不切 / 双挂统一异常 / 维度守卫拒绝 / 无备胎回归锁，另补守卫终态一条。
 */
class ResilientEmbeddingDecoratorTest {

    private static final List<String> TEXTS = List.of("小米 15 Ultra 续航怎么样");

    /** 可编程假客户端：非空 error 抛出，否则返回 result；计数验证调用序列。 */
    static class FakeEmbedding implements EmbeddingClient {
        private final String name;
        List<float[]> result;
        ModelClientException error;
        final List<String> received = new ArrayList<>();
        int callCount;

        FakeEmbedding(String name) {
            this.name = name;
        }

        @Override
        public String provider() {
            return name;
        }

        @Override
        public int dimensions() {
            return 1024;
        }

        @Override
        public List<float[]> embedAll(List<String> texts) {
            callCount++;
            received.addAll(texts);
            if (error != null) {
                throw error;
            }
            return result;
        }
    }

    private static List<float[]> vectors(int dim) {
        float[] vector = new float[dim];
        Arrays.fill(vector, 0.1f);
        return List.of(vector);
    }

    private static ModelClientException http(int status) {
        return ModelClientException.fromHttpStatus(status, "HTTP " + status + "（embedding 调用失败）");
    }

    /** §4-1：primary 429 → 切 fallback 成功，调用序列 primary 1 次 + fallback 1 次，结果为 fallback 向量。 */
    @Test
    void primary429切换备胎成功且调用序列正确() {
        FakeEmbedding primary = new FakeEmbedding("zhipu");
        primary.error = http(429);
        FakeEmbedding fallback = new FakeEmbedding("siliconflow");
        List<float[]> expected = vectors(1024);
        fallback.result = expected;
        ResilientEmbeddingDecorator decorator = new ResilientEmbeddingDecorator(primary, fallback, 1024);

        List<float[]> result = decorator.embedAll(TEXTS);

        assertThat(result).isSameAs(expected);
        assertThat(primary.callCount).isEqualTo(1);
        assertThat(fallback.callCount).isEqualTo(1);
        // 备胎收到的是同一批文本，不是降级后的残缺请求
        assertThat(fallback.received).isEqualTo(TEXTS);
        // 对上层透明：provider/dimensions 仍呈现主通道语义
        assertThat(decorator.provider()).isEqualTo("zhipu");
        assertThat(decorator.dimensions()).isEqualTo(1024);
    }

    /** §4-2：primary 401 → 不切备、原样上抛（同一异常实例），fallback 零调用。 */
    @Test
    void primary401不切备直接抛() {
        FakeEmbedding primary = new FakeEmbedding("zhipu");
        ModelClientException unauthorized = http(401);
        primary.error = unauthorized;
        FakeEmbedding fallback = new FakeEmbedding("siliconflow");
        fallback.result = vectors(1024);
        ResilientEmbeddingDecorator decorator = new ResilientEmbeddingDecorator(primary, fallback, 1024);

        ModelClientException thrown = catchThrowableOfType(
                () -> decorator.embedAll(TEXTS), ModelClientException.class);

        assertThat(thrown).isSameAs(unauthorized);
        assertThat(thrown.getType()).isEqualTo(ErrorType.UNAUTHORIZED);
        assertThat(primary.callCount).isEqualTo(1);
        assertThat(fallback.callCount).isZero();
    }

    /** §4-3：双挂 → EmbeddingUnavailableException，message 含主备双方失败摘要。 */
    @Test
    void 双挂抛统一异常且含双方摘要() {
        FakeEmbedding primary = new FakeEmbedding("zhipu");
        primary.error = http(429);
        FakeEmbedding fallback = new FakeEmbedding("siliconflow");
        fallback.error = new ModelClientException(ErrorType.NETWORK_ERROR, "siliconflow 网络异常：connection refused");
        ResilientEmbeddingDecorator decorator = new ResilientEmbeddingDecorator(primary, fallback, 1024);

        EmbeddingUnavailableException thrown = catchThrowableOfType(
                () -> decorator.embedAll(TEXTS), EmbeddingUnavailableException.class);

        assertThat(thrown.getMessage())
                .contains("zhipu").contains("siliconflow")
                .contains("429").contains("网络异常");
        assertThat(thrown.getType()).isEqualTo(ErrorType.SERVER_ERROR);
        assertThat(thrown.getCause()).isSameAs(primary.error);
        assertThat(thrown.getFallbackCause()).isSameAs(fallback.error);
        assertThat(primary.callCount).isEqualTo(1);
        assertThat(fallback.callCount).isEqualTo(1);
    }

    /** §4-4：fallback 返回 2048 维 → 维度守卫拒绝，绝不把脏向量交给上层。 */
    @Test
    void fallback维度不符被守卫拒绝() {
        FakeEmbedding primary = new FakeEmbedding("zhipu");
        primary.error = http(429);
        FakeEmbedding fallback = new FakeEmbedding("siliconflow");
        fallback.result = vectors(2048);
        ResilientEmbeddingDecorator decorator = new ResilientEmbeddingDecorator(primary, fallback, 1024);

        EmbeddingUnavailableException thrown = catchThrowableOfType(
                () -> decorator.embedAll(TEXTS), EmbeddingUnavailableException.class);

        // 守卫摘要在 message 里可诊断：期望/实际维度都可见；异常继承 ModelClientException → 对外映射 C0001
        assertThat(thrown.getMessage()).contains("维度").contains("1024").contains("2048");
        assertThat(thrown.getFallbackCause().getMessage()).contains("向量维度守卫拒绝");
        assertThat(primary.callCount).isEqualTo(1);
        assertThat(fallback.callCount).isEqualTo(1);
    }

    /** §4-5：未配置备胎 → 行为与现状一致（成功透传同一结果、失败原样上抛同一异常实例）——回归锁。 */
    @Test
    void 无备胎时行为与现状一致() {
        FakeEmbedding primary = new FakeEmbedding("zhipu");
        List<float[]> expected = vectors(1024);
        primary.result = expected;
        ResilientEmbeddingDecorator decorator = new ResilientEmbeddingDecorator(primary, 1024);

        assertThat(decorator.embedAll(TEXTS)).isSameAs(expected);

        ModelClientException rateLimited = http(429);
        primary.error = rateLimited;
        primary.result = null;
        assertThat(catchThrowableOfType(() -> decorator.embedAll(TEXTS), ModelClientException.class))
                .isSameAs(rateLimited);
        assertThat(primary.callCount).isEqualTo(2);
    }

    /** 补充：无备胎时主通道维度违规 → 守卫终态拒绝（INVALID_RESPONSE），不存在可切对象。 */
    @Test
    void 主通道维度不符守卫终态拒绝() {
        FakeEmbedding primary = new FakeEmbedding("zhipu");
        primary.result = vectors(2048);
        ResilientEmbeddingDecorator decorator = new ResilientEmbeddingDecorator(primary, 1024);

        ModelClientException thrown = catchThrowableOfType(
                () -> decorator.embedAll(TEXTS), ModelClientException.class);

        assertThat(thrown).isNotInstanceOf(EmbeddingUnavailableException.class);
        assertThat(thrown.getType()).isEqualTo(ErrorType.INVALID_RESPONSE);
        assertThat(thrown.getMessage()).contains("向量维度守卫拒绝").contains("1024").contains("2048");
    }
}
