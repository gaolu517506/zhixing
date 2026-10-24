package com.zhixing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * zhixing.llm.chat.circuit 配置段绑定（详设 13-阶段3 §11，DL-15 ④）：
 * ResilientChatDecorator 的熔断参数——连续失败阈值、熔断时长、半开试探次数。
 */
@Data
@ConfigurationProperties(prefix = "zhixing.llm.chat.circuit")
public class CircuitProperties {

    /** 连续可降级失败达到该值 → 打开熔断，直接走下一候选。 */
    private int failureThreshold = 3;

    /** 熔断持续秒数，到期后半开并放行试探。 */
    private int openSeconds = 30;

    /** 半开状态放行的试探次数，成功则闭合、失败则重新熔断。 */
    private int halfOpenAttempts = 1;
}
