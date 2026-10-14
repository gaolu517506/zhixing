package com.zhixing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * zhixing.llm 配置段绑定（02-详设 §7）。
 * fallback / firstTokenTimeoutMs 阶段 1 只绑定不消费，为阶段 4 容错降级预留。
 */
@Data
@ConfigurationProperties(prefix = "zhixing.llm")
public class LlmProperties {

    /** 供应商档案，key 为供应商标识（deepseek / zhipu / ...）。 */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    private Chat chat = new Chat();

    private Embedding embedding = new Embedding();

    private Rerank rerank = new Rerank();

    @Data
    public static class Provider {
        private String baseUrl;
        private String apiKey;
        private String chatModel;
        private String embeddingModel;
    }

    @Data
    public static class Chat {
        /** 形如 "deepseek/deepseek-chat"，取 / 前的供应商标识做路由。 */
        private String primary = "deepseek/deepseek-chat";
        private String fallback;
        private long timeoutMs = 60000;
        private long firstTokenTimeoutMs = 15000;
    }

    @Data
    public static class Embedding {
        private String provider = "zhipu";
        /** 必须与 DDL 的 vector(1024) 一致，入库后不可改。 */
        private int dimensions = 1024;
        /** 保守值；客户端分片逻辑按此切批。 */
        private int batchSize = 16;
    }

    @Data
    public static class Rerank {
        private String provider = "noop";
        private boolean enabled = false;
    }
}
