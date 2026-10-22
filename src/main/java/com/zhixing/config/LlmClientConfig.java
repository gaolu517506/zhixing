package com.zhixing.config;

import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.llm.DeepSeekChatClient;
import com.zhixing.infra.llm.NoopRerankClient;
import com.zhixing.infra.llm.RerankClient;
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

    @Bean
    public ZhipuEmbeddingClient zhipuEmbeddingClient(OkHttpClient okHttpClient, LlmProperties props) {
        LlmProperties.Provider p = providerOf(props, "zhipu");
        LlmProperties.Embedding embedding = props.getEmbedding();
        return new ZhipuEmbeddingClient(okHttpClient, p.getBaseUrl(), p.getApiKey(),
                p.getEmbeddingModel(), embedding.getDimensions(), embedding.getBatchSize());
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
