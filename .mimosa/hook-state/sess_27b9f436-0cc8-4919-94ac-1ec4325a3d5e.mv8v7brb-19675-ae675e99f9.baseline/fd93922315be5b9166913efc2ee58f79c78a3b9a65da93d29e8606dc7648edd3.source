package com.zhixing.infra.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * SSE 解析器六用例（开工包 §2.3）+ 两个容错规则扩展用例（usage 块、error 事件）。
 * 全部离线喂文本样本，不联网。
 */
class OpenAiStyleSseParserTest {

    private final OpenAiStyleSseParser parser = new OpenAiStyleSseParser();

    @Test
    void 用例1_标准流三行data加DONE拼出完整文本() {
        ParsedEvent e1 = parser.parseLine("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"你\"}}]}");
        ParsedEvent e2 = parser.parseLine("data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}");
        ParsedEvent e3 = parser.parseLine("data: {\"choices\":[{\"delta\":{\"content\":\"！\"}}]}");
        ParsedEvent e4 = parser.parseLine("data: [DONE]");

        assertThat(e1.content()).isEqualTo("你");
        assertThat(e2.content()).isEqualTo("好");
        assertThat(e3.content()).isEqualTo("！");
        assertThat(new StringBuilder().append(e1.content()).append(e2.content()).append(e3.content()).toString())
                .isEqualTo("你好！");
        assertThat(e1.done()).isFalse();
        assertThat(e4.done()).isTrue();
    }

    @Test
    void 用例2_data冒号后无空格的变体() {
        ParsedEvent event = parser.parseLine("data:{\"choices\":[{\"delta\":{\"content\":\"A\"}}]}");

        assertThat(event.content()).isEqualTo("A");
    }

    @Test
    void 用例3_空delta与content为null的首chunk() {
        ParsedEvent roleOnly = parser.parseLine("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}");
        ParsedEvent emptyDelta = parser.parseLine("data: {\"choices\":[{\"delta\":{}}]}");
        ParsedEvent explicitNull = parser.parseLine("data: {\"choices\":[{\"delta\":{\"content\":null}}]}");

        assertThat(roleOnly).isEqualTo(ParsedEvent.EMPTY);
        assertThat(emptyDelta.content()).isNull();
        assertThat(explicitNull.content()).isNull();
        assertThat(explicitNull.done()).isFalse();
    }

    @Test
    void 用例4_finishReason为stop在DONE之前到() {
        ParsedEvent finish = parser.parseLine(
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        ParsedEvent done = parser.parseLine("data: [DONE]");

        assertThat(finish.finishReason()).isEqualTo("stop");
        assertThat(finish.done()).isFalse();        // finish 不等于 done：解析器只如实报告
        assertThat(done.done()).isTrue();
    }

    @Test
    void 用例5_一行坏JSON降级EMPTY不抛异常() {
        assertThatCode(() -> {
            ParsedEvent event = parser.parseLine("data: {oops 不合法");
            assertThat(event).isEqualTo(ParsedEvent.EMPTY);
        }).doesNotThrowAnyException();
    }

    @Test
    void 用例6_reasoningContent提取() {
        ParsedEvent event = parser.parseLine(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"让我想想\"}}]}");

        assertThat(event.reasoning()).isEqualTo("让我想想");
        assertThat(event.content()).isNull();
    }

    @Test
    void 扩展1_includeUsage的最终usage块无choices() {
        ParsedEvent event = parser.parseLine(
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");

        assertThat(event.usage()).isNotNull();
        assertThat(event.usage().promptTokens()).isEqualTo(10);
        assertThat(event.usage().completionTokens()).isEqualTo(5);
    }

    @Test
    void 扩展2_流中error对象必须当失败而非静默丢弃() {
        ParsedEvent sseForm = parser.parseLine("data: {\"error\":{\"message\":\"余额不足\",\"code\":\"1113\"}}");
        ParsedEvent rawJsonForm = parser.parseLine("{\"error\":{\"message\":\"余额不足\"}}");

        assertThat(sseForm.errorMessage()).contains("余额不足");
        assertThat(rawJsonForm.errorMessage()).contains("余额不足");
    }
}
