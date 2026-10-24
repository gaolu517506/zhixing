package com.zhixing.core.memory;

/**
 * 会话轮次的单条消息（阶段 2 契约基线，详设 08-阶段2 §5）。
 * role 只有 user / assistant 两种取值，来源为 qa_message 表。
 */
public record Turn(String role, String content) {
}
