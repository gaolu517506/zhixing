package com.zhixing.core.ingest;

import java.util.List;

/**
 * 分块器 SPI。阶段 1 唯一实现 FixedSizeChunker；阶段 2 增加"标题感知"实现后对比评测。
 */
public interface Chunker {

    List<Chunk> chunk(String text, ChunkConfig cfg);
}
