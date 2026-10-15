package com.zhixing.core.ingest;

/**
 * 文档状态机（阶段 1 唯一显式状态机）：
 * UPLOADED → PARSING → CHUNKING → EMBEDDING → COMPLETED
 *     └──────────┴──────────┴──────────┴──→ FAILED(error_msg)
 * 约定：每进入一个节点先落库 status 再干活；节点内异常 → FAILED + 对外安全文案。
 */
public enum DocumentStatus {
    UPLOADED,
    PARSING,
    CHUNKING,
    EMBEDDING,
    COMPLETED,
    FAILED
}
