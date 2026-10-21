/**
 * 核心域 · 检索引擎（链路 B 前半）：双通道并行 → RRF 融合 → rerank 精排。
 *
 * <p>核心类：RetrievalEngine（并行编排 + 降级语义——任一通道异常记 FAILED 节点不抛出）、
 * RrfFuser（纯函数，rank 唯一公共语言）、RetrieveBudget/CitedChunk/RetrievalResult。
 *
 * <p>依赖规则：消费 infra/vector、infra/keyword、infra/llm 的接口；
 * keywords 重载（阶段 2）由 zhixing.rewrite.keyword-channel 开关分流，D6 裁决数据源。
 */
package com.zhixing.core.retrieval;
