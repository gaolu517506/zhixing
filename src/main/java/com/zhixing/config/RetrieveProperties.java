package com.zhixing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * zhixing.retrieve 配置段绑定（检索问答线专属；详设 §7 的 retrieve 段 + 线程池参数）。
 */
@Data
@ConfigurationProperties(prefix = "zhixing.retrieve")
public class RetrieveProperties {

    private int vectorTopK = 20;

    private int keywordTopK = 20;

    /** RRF 融合常数 k（RRF(d) = Σ 1/(k + rank)）。 */
    private int rrfK = 60;

    /** 融合排序后的最终返回条数（rerank 关闭时直接截断到这里）。 */
    private int finalTopK = 5;

    /** 关键词通道实现：trgm（默认，similarity + % 操作符）| ilike（保底）。 */
    private String keywordMode = "trgm";

    /** 双通道并行池。 */
    private int poolCore = 4;

    private int poolMax = 8;
}
