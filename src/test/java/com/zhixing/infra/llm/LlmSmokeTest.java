package com.zhixing.infra.llm;

import com.zhixing.infra.llm.dto.ChatMessage;
import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatResponse;
import com.zhixing.infra.llm.dto.ChatUsage;
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
 * 冒烟测试 1/2/4（真实调用，花钱）：默认跳过，LLM_SMOKE=true 时才跑。
 * 冒烟 3（切 primary 到 zhipu）在 {@link LlmZhipuPrimarySmokeTest}。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "LLM_SMOKE", matches = "true")
class LlmSmokeTest {

    @Autowired
    private ChatClient primaryChatClient;       // yaml 默认路由 deepseek

    @Autowired
    private EmbeddingClient embeddingClient;

    @BeforeAll
    static void requireKeys() {
        assumeTrue(System.getenv("DEEPSEEK_API_KEY") != null && !System.getenv("DEEPSEEK_API_KEY").isBlank(),
                "缺少 DEEPSEEK_API_KEY，跳过冒烟");
        assumeTrue(System.getenv("ZHIPU_API_KEY") != null && !System.getenv("ZHIPU_API_KEY").isBlank(),
                "缺少 ZHIPU_API_KEY，跳过冒烟");
    }

    /** 冒烟 1：deepseek 同步问答非空。 */
    @Test
    void 冒烟1_deepseek同步问答非空() {
        ChatResponse response = primaryChatClient.call(ChatRequest.builder()
                .messages(List.of(ChatMessage.user("用一句话介绍 deepseek-chat")))
                .maxTokens(100)
                .build());

        assertThat(response.content()).isNotBlank();
        assertThat(response.finishReason()).isEqualTo("stop");
        System.out.println("[冒烟1] " + response.content());
    }

    /** 冒烟 2：deepseek 流式增量拼接非空、finishReason=stop。 */
    @Test
    void 冒烟2_deepseek流式拼接非空且终态stop() {
        StringBuilder content = new StringBuilder();
        AtomicReference<String> finishReason = new AtomicReference<>();
        AtomicReference<ChatUsage> usage = new AtomicReference<>();

        primaryChatClient.stream(ChatRequest.builder()
                        .messages(List.of(ChatMessage.user("从 1 数到 5，用顿号分隔")))
                        .maxTokens(50)
                        .build(),
                new StreamCallback() {
                    @Override
                    public void onContent(String delta) {
                        content.append(delta);
                        System.out.print(delta);
                    }

                    @Override
                    public void onComplete(String finish, ChatUsage u) {
                        finishReason.set(finish);
                        usage.set(u);
                    }

                    @Override
                    public void onError(Throwable t) {
                        throw new AssertionError("流式调用失败", t);
                    }
                });
        System.out.println();

        assertThat(content).isNotEmpty();
        assertThat(finishReason).hasValue("stop");
        System.out.println("[冒烟2] usage=" + usage.get());
    }

    /** 冒烟 4：zhipu embedding 两条文本都是 1024 维。 */
    @Test
    void 冒烟4_zhipuEmbedding两条均1024维() {
        List<float[]> vectors = embeddingClient.embedAll(List.of("小米15 Ultra 的电池容量是多少", "Redmi K80 的屏幕参数"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).hasSize(embeddingClient.dimensions()).hasSize(1024);
        assertThat(vectors.get(1)).hasSize(1024);
    }
}
