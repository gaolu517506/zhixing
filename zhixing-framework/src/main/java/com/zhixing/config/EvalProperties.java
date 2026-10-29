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

    // ===== 以下为验收向导（F6，v2.0 M2）配置 =====

    /** eval-run 专属执行池（与检索池隔离——评测逐题检索会占满检索线程，不能饿死在线问答）。 */
    private int poolCore = 1;

    private int poolMax = 2;

    /** 内联评估集（setJson）最大字符数护栏：防止超大请求体整段落进 config。 */
    private int maxInlineSetChars = 200_000;
}
