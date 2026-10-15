package com.zhixing.core.ingest;

/**
 * 分块配置：size 为目标块长（字符），overlap 为相邻块尾部重叠（字符）。
 * 取值来自所属知识库（kb_knowledge_base.chunk_size/chunk_overlap，DDL 默认 500/50）。
 */
public record ChunkConfig(int size, int overlap) {

    public static final ChunkConfig DEFAULT = new ChunkConfig(500, 50);

    public ChunkConfig {
        if (size <= 0) {
            throw new IllegalArgumentException("chunk size 必须 > 0，实际：" + size);
        }
        if (overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("chunk overlap 必须在 [0, size) 内，实际：" + overlap);
        }
    }
}
