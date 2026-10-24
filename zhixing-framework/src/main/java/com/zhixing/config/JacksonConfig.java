package com.zhixing.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Long/long → String 序列化：雪花 ID 超出 JS Number 的 53 位安全整数，前端读 id 会精度丢失——
 * 阶段 1 埋的坑（开工包"已知的坑"第 2 条）在 v0.1 前端接入前偿还。
 * 只挂 Long 与 long：promptTokens 等统计字段是 Integer/int，不受影响。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> builder
                .serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(Long.TYPE, ToStringSerializer.instance);
    }
}
