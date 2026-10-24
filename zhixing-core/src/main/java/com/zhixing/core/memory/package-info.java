/**
 * 核心域 · 会话记忆（阶段 2）：问题重写 + 窗口记忆 + 滚动摘要。
 *
 * <p>核心类：SessionMemory（窗口偶数对齐，尾部半轮不进窗）、Summarizer（滚动摘要）、
 * QueryRewriter 接口（@Primary LLM 实现 + Noop 兜底）、Turn/MemoryView/RewrittenQuery 契约。
 *
 * <p>依赖规则：消费 infra/llm 接口；重写失败/摘要失败降级不终态（DL-14 ③）由编排方保证。
 */
package com.zhixing.core.memory;
