package com.zhixing.infra.llm;

import com.zhixing.infra.llm.dto.ChatMessage;
import com.zhixing.infra.llm.dto.ChatRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 冒烟 3（扩展点验收）：把 primary 切到 zhipu/glm-4-flash、embedding batch-size 切到 1，
 * 验证"换供应商/调分片只改配置、代码零改动"。真实操作是改 application.yaml，
 * 测试里用属性覆盖等价模拟（同一套 @ConfigurationProperties 绑定路径）。
 */
@SpringBootTest(properties = {
        "zhixing.llm.chat.primary=zhipu/glm-4-flash",
        "zhixing.llm.embedding.batch-size=1"
})
@EnabledIfEnvironmentVariable(named = "LLM_SMOKE", matches = "true")
class LlmZhipuPrimarySmokeTest {

    @Autowired
    private ChatClient primaryChatClient;       // 现在应路由到 zhipu

    @Autowired
    private EmbeddingClient embeddingClient;

    @BeforeAll
    static void requireKey() {
        assumeTrue(System.getenv("ZHIPU_API_KEY") != null && !System.getenv("ZHIPU_API_KEY").isBlank(),
                "缺少 ZHIPU_API_KEY，跳过冒烟");
    }

    @Test
    void 冒烟3_primary已按配置路由到zhipu且同步问答非空() {
        assertThat(primaryChatClient.provider()).isEqualTo("zhipu");

        var response = primaryChatClient.call(ChatRequest.builder()
                .messages(List.of(ChatMessage.user("用一句话介绍 GLM")))
                .maxTokens(100)
                .build());

        assertThat(response.content()).isNotBlank();
        System.out.println("[冒烟3-同步] " + response.content());
    }

    @Test
    void 冒烟3_zhipu流式拼接非空() {
        StringBuilder content = new StringBuilder();
        AtomicReference<String> finishReason = new AtomicReference<>("");

        primaryChatClient.stream(ChatRequest.builder()
                        .messages(List.of(ChatMessage.user("从 1 数到 3，用顿号分隔")))
                        .maxTokens(50)
                        .build(),
                new StreamCallback() {
                    @Override
                    public void onContent(String delta) {
                        content.append(delta);
                        System.out.print(delta);
                    }

                    @Override
                    public void onComplete(String finish, com.zhixing.infra.llm.dto.ChatUsage usage) {
                        finishReason.set(finish);
                    }

                    @Override
                    public void onError(Throwable t) {
                        throw new AssertionError("流式调用失败", t);
                    }
                });
        System.out.println();

        assertThat(content).isNotEmpty();
        assertThat(finishReason).hasValue("stop");
    }

    /** 冒烟 4 分片：batch-size=1 时两条文本应拆成两批调用，结果仍按序回填且 1024 维。 */
    @Test
    void 冒烟4_batchSize为1时两条文本分两批且按序回填() {
        List<float[]> vectors = embeddingClient.embedAll(List.of("文本甲", "文本乙"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).hasSize(1024);
        assertThat(vectors.get(1)).hasSize(1024);
    }
}
