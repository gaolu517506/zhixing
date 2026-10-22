package com.zhixing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * zhixing.agent 配置段绑定（详设 13-阶段3 §12）：ReAct 循环与工具执行参数。
 */
@Data
@ConfigurationProperties(prefix = "zhixing.agent")
public class AgentProperties {

    /** 最大迭代轮数，耗尽后注入提示强制无工具作答（DL-15 ②）。 */
    private int maxIterations = 10;

    /** messages 水位（字符数），超限裁剪工具结果 → 仍超限 LLM 摘要压缩（DL-15 ①）。 */
    private int maxContextChars = 100_000;

    /** 单工具执行超时（超时以错误结果回填，让模型自纠）。 */
    private long toolTimeoutMs = 30_000;
}
