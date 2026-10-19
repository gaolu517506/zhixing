package com.zhixing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * zhixing.summary 配置段绑定（详设 08-阶段2 §6）：滚动摘要参数。
 * 第 9 轮触发、≤200 字为作者校准值（概设 §8 上下文成本条目）。
 */
@Data
@ConfigurationProperties(prefix = "zhixing.summary")
public class SummaryProperties {

    /** 完成轮数达到该值时，在本轮提问到达时同步滚动摘要。 */
    private int triggerTurns = 9;

    /** 摘要最大字符数。 */
    private int maxChars = 200;
}
