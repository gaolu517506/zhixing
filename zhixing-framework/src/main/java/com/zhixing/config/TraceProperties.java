package com.zhixing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * zhixing.trace 配置段绑定。enabled=false 时 TraceRecorder 全部退化为直通，
 * 零表写入、零额外开销（Trace 边写边埋点，开关留给压测与生产）。
 */
@Data
@ConfigurationProperties(prefix = "zhixing.trace")
public class TraceProperties {

    private boolean enabled = true;

    /** node input/output 落库截断长度。 */
    private int maxTextLength = 2000;
}
