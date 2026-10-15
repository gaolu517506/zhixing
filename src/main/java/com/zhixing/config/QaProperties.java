package com.zhixing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * zhixing.qa 配置段绑定（问答编排线专属）。
 */
@Data
@ConfigurationProperties(prefix = "zhixing.qa")
public class QaProperties {

    /** QA 全链路执行池（检索通道在 retrieval-pool，两者隔离避免嵌套提交饿死）。 */
    private int poolCore = 2;

    private int poolMax = 4;

    /** SseEmitter 超时：15 分钟（概设 §3.4）。 */
    private long emitterTimeoutMs = 900_000;
}
