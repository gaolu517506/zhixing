package com.zhixing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * zhixing.route 配置段绑定（意图路由线专属，02-技术方案 §B10）。
 */
@Data
@ConfigurationProperties(prefix = "zhixing.route")
public class RouteProperties {

    /** 意图路由总开关：false 时问答必须显式选库（前端库选择即事实上的路由）。 */
    private boolean enabled = true;

    /** LLM 分类失败/低置信的兜底即回落全集——本开关控制是否尝试 LLM 级（false=仅关键词级）。 */
    private boolean llmEnabled = true;
}
