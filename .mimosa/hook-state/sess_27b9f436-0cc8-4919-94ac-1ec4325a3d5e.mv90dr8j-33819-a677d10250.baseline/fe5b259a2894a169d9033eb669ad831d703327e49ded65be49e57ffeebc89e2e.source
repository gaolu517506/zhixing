package com.zhixing.infra.llm;

import java.util.List;

/**
 * 向量嵌入客户端。
 */
public interface EmbeddingClient {

    String provider();

    /**
     * 批量嵌入：内部按客户端自己的 maxBatchSize 分片逐批调用，结果按输入顺序回填——
     * 返回列表第 i 个元素永远对应第 i 个输入文本。
     */
    List<float[]> embedAll(List<String> texts);

    default float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    /** 向量维度，必须与 DDL 的 vector(1024) 一致；换模型即换维度，需重建向量列。 */
    int dimensions();
}
