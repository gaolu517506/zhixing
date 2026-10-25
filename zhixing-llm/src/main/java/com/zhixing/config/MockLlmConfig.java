package com.zhixing.config;

import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.llm.EmbeddingClient;
import com.zhixing.infra.llm.StreamCallback;
import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatResponse;
import com.zhixing.infra.llm.dto.ChatUsage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;

import java.util.List;

/**
 * 开发/演示专用桩模型（--spring.profiles.active=mock-llm 激活）：
 * 用于 SSE 事件序验证、前端联调、评测旁路打库等一切"不该花钱调真模型"的场景。
 *
 * <p>实现方式：注册期后处理器把 D5 装配的真实模型 bean（deepSeek/zhipu/primary/embedding）
 * 从容器里摘除，换成本类的固定行为桩——检索与问答链路代码零感知，注入的仍是 ChatClient/EmbeddingClient。
 * 普通 profile（含测试与生产）下本类整体不加载，零影响。
 */
@Configuration(proxyBeanMethods = false)
@Profile("mock-llm")
public class MockLlmConfig implements BeanDefinitionRegistryPostProcessor {

    private static final String[] REAL_LLM_BEANS = {
            "deepSeekChatClient", "zhipuChatClient", "primaryChatClient", "embeddingClient"
    };

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        for (String name : REAL_LLM_BEANS) {
            if (registry.containsBeanDefinition(name)) {
                registry.removeBeanDefinition(name);
            }
        }
        registerStub(registry, "stubChatClient", StubChatClient.class);
        registerStub(registry, "stubEmbeddingClient", StubEmbeddingClient.class);
    }

    private void registerStub(BeanDefinitionRegistry registry, String name, Class<?> type) {
        BeanDefinition definition = BeanDefinitionBuilder.genericBeanDefinition(type).getBeanDefinition();
        registry.registerBeanDefinition(name, definition);
    }

    @Override
    public void postProcessBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
        // 注册期已换桩，无需后处理
    }

    /** 固定回答 + 固定事件序（两段 delta + stop 终态），供 SSE 时序实测与联调。 */
    public static class StubChatClient implements ChatClient {

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public ChatResponse call(ChatRequest request) {
            return new ChatResponse("[stub] 知识库中暂无该问题的答案，此为桩模型的固定回复。", "stop",
                    new ChatUsage(16, 24));
        }

        @Override
        public void stream(ChatRequest request, StreamCallback callback) {
            callback.onContent("小米 15 Ultra 搭载");
            callback.onContent("6000mAh 小米金沙江电池 [1]。");
            callback.onComplete("stop", new ChatUsage(18, 10));
        }
    }

    /** 固定向量：全 0.05 × 1024 维，与检索假数据字面量一致（余弦相似=1，可离线验证 HNSW 通道）。 */
    public static class StubEmbeddingClient implements EmbeddingClient {

        private static final float FIXED_VALUE = 0.05f;

        private static final int DIMENSIONS = 1024;

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public List<float[]> embedAll(List<String> texts) {
            float[] vector = fixedVector();
            return texts.stream().<float[]>map(t -> vector).toList();
        }

        @Override
        public int dimensions() {
            return DIMENSIONS;
        }

        private static float[] fixedVector() {
            float[] vector = new float[DIMENSIONS];
            java.util.Arrays.fill(vector, FIXED_VALUE);
            return vector;
        }
    }
}
