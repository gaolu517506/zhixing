package com.zhixing.service.agenttools;

import com.zhixing.core.agent.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** get_current_time 单测：输出可读时间与时区标识。 */
class GetCurrentTimeToolTest {

    private final GetCurrentTimeTool tool = new GetCurrentTimeTool();

    @Test
    void 返回本地时间与时区() {
        ToolResult result = tool.execute(Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.content())
                .matches("当前时间：\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}（时区 .+）");
    }

    @Test
    void 无参工具_空参数也正常执行() {
        assertThat(tool.execute(null).success()).isTrue();
    }

    @Test
    void 契约字段_name与requireConfirm() {
        assertThat(tool.name()).isEqualTo("get_current_time");
        assertThat(tool.requireConfirm()).isFalse();
    }
}
