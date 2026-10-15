package com.zhixing.infra.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分片逻辑纯函数单测（离线、免费）；端到端分片由冒烟 4 以 batch-size=1 验证。
 */
class ZhipuEmbeddingClientTest {

    @Test
    void 分片_不整除时最后一片为余量() {
        List<List<String>> shards = ZhipuEmbeddingClient.partition(List.of("a", "b", "c", "d", "e"), 2);

        assertThat(shards).containsExactly(List.of("a", "b"), List.of("c", "d"), List.of("e"));
    }

    @Test
    void 分片_恰好整除() {
        List<List<String>> shards = ZhipuEmbeddingClient.partition(List.of("a", "b", "c", "d"), 2);

        assertThat(shards).containsExactly(List.of("a", "b"), List.of("c", "d"));
    }

    @Test
    void 分片_批大于总数时单片() {
        List<List<String>> shards = ZhipuEmbeddingClient.partition(List.of("a", "b"), 16);

        assertThat(shards).containsExactly(List.of("a", "b"));
    }

    @Test
    void 分片_空输入返回空() {
        assertThat(ZhipuEmbeddingClient.partition(List.of(), 16)).isEmpty();
    }

    @Test
    void 分片_非法批大小炸出_IllegalArgumentException() {
        assertThatThrownBy(() -> ZhipuEmbeddingClient.partition(List.of("a"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
