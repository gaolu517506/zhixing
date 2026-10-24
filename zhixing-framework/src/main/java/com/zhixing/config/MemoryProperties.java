package com.zhixing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * zhixing.memory 配置段绑定：会话窗口参数 + 长期记忆开关（详设 14-v1.0 §3.4，DL-17 ①②）。
 * 8 轮为作者校准值；窗口 token 预算 ≈ 8×500 字 + 摘要 200 字 ≈ 2.6k token。
 */
@Data
@ConfigurationProperties(prefix = "zhixing.memory")
public class MemoryProperties {

    /** 记忆窗口大小（完整 user+assistant 轮数）。 */
    private int windowSize = 8;

    /** 长期记忆（回合级异步仲裁 + 跨会话注入）。 */
    private LongTerm longTerm = new LongTerm();

    @Data
    public static class LongTerm {

        /** 长期记忆总开关（回合级异步仲裁 + 双链路注入）。 */
        private boolean enabled = true;

        /** 注入的单次上限条数（超限取最近更新）。 */
        private int maxInject = 20;
    }
}
