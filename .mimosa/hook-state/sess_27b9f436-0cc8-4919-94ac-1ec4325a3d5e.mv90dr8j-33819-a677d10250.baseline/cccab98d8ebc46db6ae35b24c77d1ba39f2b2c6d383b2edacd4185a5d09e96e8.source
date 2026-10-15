package com.zhixing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * zhixing.eval 配置段绑定（评测旁路，决策 D11）。
 * enabled 默认 false：旁路接口整个不存在（真 404），生产零开销。
 */
@Data
@ConfigurationProperties(prefix = "zhixing.eval")
public class EvalProperties {

    private boolean enabled = false;

    /** 旁路防误用密钥，与请求头 X-Eval-Key 比对。 */
    private String key = "local-eval-key";

    /** 评估集 jsonl 路径（EvalRunner 读）。 */
    private String setPath = "docs/scenario/eval-set.jsonl";

    /** 评估目标知识库；真实运行前必须配置为合法 kbId。 */
    private long kbId = 0;
}
