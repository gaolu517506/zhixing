package com.zhixing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.common.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Long→String 全局序列化（任务书 §2）：走 Spring 上下文自动装配的 ObjectMapper
 * （与 MVC 输出同一实例），断言 Long/long → 字符串、Integer/int 保持数字——
 * 精确命中包装与原始两类，不误伤统计字段。
 */
@SpringBootTest
class LongToStringSerializationTest {

    @Autowired
    private ObjectMapper objectMapper;

    record Sample(Long id, long raw, Integer tokens, int code) {
    }

    @Test
    void Long与long输出字符串_Integer与int保持数字() throws Exception {
        String json = objectMapper.writeValueAsString(new Sample(2110626172473077760L, 7L, 18, 5));

        assertThat(json).contains("\"id\":\"2110626172473077760\"");
        assertThat(json).contains("\"raw\":\"7\"");
        assertThat(json).contains("\"tokens\":18");
        assertThat(json).contains("\"code\":5");
    }

    @Test
    void Result包装的雪花id也是字符串() throws Exception {
        String json = objectMapper.writeValueAsString(Result.ok(Map.of("kbId", 2110626172473077760L)));

        assertThat(json).contains("\"kbId\":\"2110626172473077760\"");
        assertThat(json).contains("\"code\":\"0\"");
    }
}
