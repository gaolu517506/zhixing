package com.zhixing.core.ingest;

/**
 * 分块产物。tokenCount 为估算值：chars/1.6（决策 D8，中文场景的粗估足够阶段 1 用）。
 */
public record Chunk(int index, String content, int tokenCount) {

    public static Chunk of(int index, String content) {
        return new Chunk(index, content, (int) Math.round(content.length() / 1.6));
    }
}
