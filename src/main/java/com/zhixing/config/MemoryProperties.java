package com.zhixing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * zhixing.memory 配置段绑定（详设 08-阶段2 §5）：会话窗口参数。
 * 8 轮为作者校准值；窗口 token 预算 ≈ 8×500 字 + 摘要 200 字 ≈ 2.6k token。
 */
@Data
@ConfigurationProperties(prefix = "zhixing.memory")
public class MemoryProperties {

    /** 记忆窗口大小（完整 user+assistant 轮数）。 */
    private int windowSize = 8;
}
