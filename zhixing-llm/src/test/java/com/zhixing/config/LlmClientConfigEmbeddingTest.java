package com.zhixing.config;

import com.zhixing.infra.llm.EmbeddingClient;
import com.zhixing.infra.llm.ResilientEmbeddingDecorator;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * embedding 装配测试（任务书 v1.1-B §3.1/§3.3，直接调 bean 方法，离线）：
 * 备胎二段式解析（model 段含 /）、provider 未配置/解析失败启动 FAIL-FAST、空备胎单通道装配。
 */
class LlmClientConfigEmbeddingTest {

    private final LlmClientConfig config = new LlmClientConfig();

    private static LlmProperties propsWithZhipu() {
        LlmProperties props = new LlmProperties();
        LlmProperties.Provider zhipu = new LlmProperties.Provider();
        zhipu.setBaseUrl("https://open.bigmodel.cn/api/paas/v4");
        zhipu.setApiKey("test-key");
        zhipu.setEmbeddingModel("embedding-3");
        props.getProviders().put("zhipu", zhipu);
        return props;
    }

    /** fallback 为空：装配单通道装饰器，维度/主 provider 呈现与现状一致。 */
    @Test
    void fallback为空时装配单通道() {
        EmbeddingClient client = config.embeddingClient(new OkHttpClient(), propsWithZhipu());

        assertThat(client).isInstanceOf(ResilientEmbeddingDecorator.class);
        assertThat(client.provider()).isEqualTo("zhipu");
        assertThat(client.dimensions()).isEqualTo(1024);
    }

    /** fallback 指向已配置 provider：装配成功，对上层仍呈现主通道。 */
    @Test
    void fallback指向已配置provider时装配成功() {
        LlmProperties props = propsWithZhipu();
        LlmProperties.Provider siliconflow = new LlmProperties.Provider();
        siliconflow.setBaseUrl("https://api.siliconflow.cn/v1");
        props.getProviders().put("siliconflow", siliconflow);
        props.getEmbedding().setFallback("siliconflow/BAAI/bge-m3");

        EmbeddingClient client = config.embeddingClient(new OkHttpClient(), props);

        assertThat(client).isInstanceOf(ResilientEmbeddingDecorator.class);
        assertThat(client.provider()).isEqualTo("zhipu");
        assertThat(client.dimensions()).isEqualTo(1024);
    }

    /** fallback 的 provider 未在 providers 里配置：启动 FAIL-FAST 并指明是哪个 provider。 */
    @Test
    void fallbackProvider未配置时启动失败() {
        LlmProperties props = propsWithZhipu();
        props.getEmbedding().setFallback("siliconflow/BAAI/bge-m3");

        assertThatThrownBy(() -> config.embeddingClient(new OkHttpClient(), props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("siliconflow");
    }

    /** 解析失败（缺 model 段）：启动 FAIL-FAST。 */
    @Test
    void fallback缺model段时启动失败() {
        LlmProperties props = propsWithZhipu();
        props.getEmbedding().setFallback("siliconflow/");

        assertThatThrownBy(() -> config.embeddingClient(new OkHttpClient(), props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider/model");
    }

    /** 二段式解析：按第一个 / 切分，model 段可含 /（如 BAAI/bge-m3）。 */
    @Test
    void 二段式解析按第一个斜杠切分() {
        LlmClientConfig.EmbeddingFallback fallback = LlmClientConfig.parseFallbackSpec("siliconflow/BAAI/bge-m3");
        assertThat(fallback.provider()).isEqualTo("siliconflow");
        assertThat(fallback.model()).isEqualTo("BAAI/bge-m3");
    }
}
