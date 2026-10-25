package com.zhixing.config;

import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.llm.DeepSeekChatClient;
import com.zhixing.infra.llm.EmbeddingClient;
import com.zhixing.infra.llm.NoopRerankClient;
import com.zhixing.infra.llm.RerankClient;
import com.zhixing.infra.llm.ResilientEmbeddingDecorator;
import com.zhixing.infra.llm.ZhipuChatClient;
import com.zhixing.infra.llm.ZhipuEmbeddingClient;
import okhttp3.OkHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 客户端装配。各供应商 bean 显式注册而非组件扫描——客户端是"配置的函数"，
 * 构造参数全部来自 yaml。primaryChatClient 按 yaml 直选；阶段 4 升级为带 fallback
 * 的装饰器时只动这一个 bean，接口与调用方零改动。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmClientConfig {

    @Bean
    public DeepSeekChatClient deepSeekChatClient(OkHttpClient okHttpClient, LlmProperties props) {
        LlmProperties.Provider p = providerOf(props, "deepseek");
        return new DeepSeekChatClient(okHttpClient, p.getBaseUrl(), p.getApiKey(), p.getChatModel());
    }

    @Bean
    public ZhipuChatClient zhipuChatClient(OkHttpClient okHttpClient, LlmProperties props) {
        LlmProperties.Provider p = providerOf(props, "zhipu");
        return new ZhipuChatClient(okHttpClient, p.getBaseUrl(), p.getApiKey(), p.getChatModel());
    }

    /**
     * embedding 链路装配（v1.1-B T5-2，DL-19）：主 + 可选备胎（请求级切换 + 维度守卫），
     * 对消费方（IngestionPipeline/RetrievalEngine）零感知。fallback 为空时行为与现状一致。
     */
    @Bean
    public EmbeddingClient embeddingClient(OkHttpClient okHttpClient, LlmProperties props) {
        LlmProperties.Embedding embedding = props.getEmbedding();
        LlmProperties.Provider p = providerOf(props, embedding.getProvider());
        ZhipuEmbeddingClient primary = new ZhipuEmbeddingClient(okHttpClient, p.getBaseUrl(), p.getApiKey(),
                p.getEmbeddingModel(), embedding.getDimensions(), embedding.getBatchSize(), embedding.getProvider());
        String fallbackSpec = embedding.getFallback();
        if (isBlank(fallbackSpec)) {
            log.info("[embedding] 未配置备胎，单通道运行（provider={}，dimensions={}）",
                    embedding.getProvider(), embedding.getDimensions());
            return new ResilientEmbeddingDecorator(primary, embedding.getDimensions());
        }
        EmbeddingFallback fallback = parseFallbackSpec(fallbackSpec);
        // provider 未配置（缺 baseUrl）：providerOf 启动即炸——配置错误绝不留到调用期（任务书 §3.1 FAIL-FAST）
        LlmProperties.Provider fp = providerOf(props, fallback.provider());
        ZhipuEmbeddingClient fallbackClient = new ZhipuEmbeddingClient(okHttpClient, fp.getBaseUrl(), fp.getApiKey(),
                fallback.model(), embedding.getDimensions(), embedding.getBatchSize(), fallback.provider());
        log.warn("[embedding] 已启用备胎 {}/{}——主备须同维度模型（当前 {} 维），否则切换后向量语义空间不一致",
                fallback.provider(), fallback.model(), embedding.getDimensions());
        return new ResilientEmbeddingDecorator(primary, fallbackClient, embedding.getDimensions());
    }

    /** 备胎取值 provider/model 二段；model 段可含 /（如 BAAI/bge-m3），按第一个 / 切分。 */
    record EmbeddingFallback(String provider, String model) {
    }

    static EmbeddingFallback parseFallbackSpec(String spec) {
        int slash = spec.indexOf('/');
        if (slash <= 0 || slash == spec.length() - 1) {
            throw new IllegalStateException("zhixing.llm.embedding.fallback 配置不合法"
                    + "（应为 provider/model 二段，如 siliconflow/BAAI/bge-m3）：" + spec);
        }
        return new EmbeddingFallback(spec.substring(0, slash), spec.substring(slash + 1));
    }

    @Bean
    public RerankClient rerankClient() {
        // 阶段 1 只有 Noop；接入真实 rerank 时按 props.getRerank().getProvider() 分流
        return new NoopRerankClient();
    }

    /** yaml 路由：取 primary 的供应商标识直选对应客户端（model 段暂不消费，provider 的 chat-model 即默认）。 */
    @Bean
    public ChatClient primaryChatClient(LlmProperties props,
                                        DeepSeekChatClient deepSeekChatClient,
                                        ZhipuChatClient zhipuChatClient) {
        String primary = props.getChat().getPrimary();
        String provider = primary.contains("/")
                ? primary.substring(0, primary.indexOf('/'))
                : primary;
        return switch (provider) {
            case "deepseek" -> deepSeekChatClient;
            case "zhipu" -> zhipuChatClient;
            default -> throw new IllegalStateException("zhixing.llm.chat.primary 指向未知供应商：" + primary);
        };
    }

    /** baseUrl 是结构性配置，缺失直接炸在启动期；apiKey 允许为空（本地无 key 也能起，调用时 401 暴露）。 */
    private static LlmProperties.Provider providerOf(LlmProperties props, String name) {
        LlmProperties.Provider p = props.getProviders().get(name);
        if (p == null || isBlank(p.getBaseUrl())) {
            throw new IllegalStateException("zhixing.llm.providers." + name + " 配置不完整（缺 baseUrl）");
        }
        if (isBlank(p.getApiKey())) {
            log.warn("zhixing.llm.providers.{} 未配置 apiKey，该供应商调用将返回 401", name);
        }
        return p;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
