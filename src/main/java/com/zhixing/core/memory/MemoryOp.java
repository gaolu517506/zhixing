package com.zhixing.core.memory;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 记忆仲裁操作（详设 14-v1.0 §3.3）。action：ADD / SUPERSEDE / RETRACT；
 * CLEAR 表达为对全部 ACTIVE 逐条 RETRACT（不设独立动作）。
 * target_id 仅 SUPERSEDE/RETRACT 携带；content 仅 ADD/SUPERSEDE 携带。
 */
public record MemoryOp(
        @JsonProperty("action") String action,
        @JsonProperty("target_id") Long targetId,
        @JsonProperty("content") String content) {

    public static final String ADD = "ADD";
    public static final String SUPERSEDE = "SUPERSEDE";
    public static final String RETRACT = "RETRACT";
}
