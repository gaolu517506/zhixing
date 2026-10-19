package com.zhixing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * zhixing.rewrite 配置段绑定（详设 08-阶段2 §4/§8）：问题重写与关键词通道开关。
 * keyword-channel 是 D6 退出策略的对比开关：关闭时关键词通道退回整句匹配（阶段 1 行为），
 * eval delta 直接回答"数组匹配值不值得"。
 */
@Data
@ConfigurationProperties(prefix = "zhixing.rewrite")
public class RewriteProperties {

    /** 问题重写总开关（关闭 = 阶段 1 单轮行为）。 */
    private boolean enabled = true;

    /** 重写产出的关键词数组是否进入检索（D6 对比开关）。 */
    private boolean keywordChannel = true;
}
