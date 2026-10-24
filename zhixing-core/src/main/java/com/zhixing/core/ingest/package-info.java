/**
 * 核心域 · 入库管线（链路 A）：解析 → 分块 → 嵌入 → 写入，异步执行、状态机落库。
 *
 * <p>核心类：IngestionPipeline（编排与 batch 级重试 DL-11）、FixedSizeChunker（切分五规则纯函数）、
 * TikaDocumentParser、DocumentStatus 状态机。
 *
 * <p>依赖规则：core 层不 import jakarta.servlet；消费 infra/llm 的 EmbeddingClient 接口与
 * infra/storage 的 FileStorage 接口（依赖倒置），写库经 service 层事务方法。
 */
package com.zhixing.core.ingest;
