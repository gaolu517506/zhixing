package com.zhixing.core.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * RRF 融合三用例（详设 §8 必测：单通道 / 双通道同 item / 空通道降级）。
 * worked example 对齐详设 §4.2 的表格（k=60）。
 */
class RrfFuserTest {

    private static CitedChunk chunk(long id) {
        return new CitedChunk(id, id, "doc-" + id, "内容" + id, 0.9);
    }

    @Test
    void 用例1_单通道按RRF排名递减() {
        List<CitedChunk> fused = RrfFuser.fuse(List.of(chunk(1), chunk(2), chunk(3)), List.of(), 60);

        assertThat(fused).extracting(CitedChunk::chunkId).containsExactly(1L, 2L, 3L);
        assertThat(fused.get(0).score()).isEqualTo(1.0 / 61, within(1e-9));
        assertThat(fused.get(1).score()).isEqualTo(1.0 / 62, within(1e-9));
        assertThat(fused.get(2).score()).isEqualTo(1.0 / 63, within(1e-9));
    }

    @Test
    void 用例2_双通道同item分数取并集_两路认可者浮顶() {
        // 详设 §4.2 worked example：c1(A1,B3)、c2(A3,B1)、c3(仅A2)、c4(仅B2)
        List<CitedChunk> a = List.of(chunk(1), chunk(3), chunk(2));
        List<CitedChunk> b = List.of(chunk(2), chunk(4), chunk(1));

        List<CitedChunk> fused = RrfFuser.fuse(a, b, 60);

        assertThat(fused).extracting(CitedChunk::chunkId).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
        // c1 = 1/61 + 1/63，c2 = 1/63 + 1/61 —— 数学并列，同分组内顺序未定义，按分数组断言
        double c1 = scoreOf(fused, 1L);
        double c2 = scoreOf(fused, 2L);
        assertThat(c1).isEqualTo(1.0 / 61 + 1.0 / 63, within(1e-9));
        assertThat(c2).isEqualTo(c1, within(1e-12));
        // 单通道 chunk 被压后：c3 = c4 = 1/62 ≈ 0.0161 < 0.0323
        assertThat(scoreOf(fused, 3L)).isEqualTo(1.0 / 62, within(1e-9));
        assertThat(scoreOf(fused, 4L)).isEqualTo(1.0 / 62, within(1e-9));
        // 双路认可者排在前两组
        assertThat(fused.get(0).score()).isGreaterThan(fused.get(2).score());
    }

    private static double scoreOf(List<CitedChunk> fused, long chunkId) {
        return fused.stream().filter(c -> c.chunkId() == chunkId).findFirst().orElseThrow().score();
    }

    @Test
    void 用例3_空通道自然退化为单通道排名() {
        List<CitedChunk> keywordOnly = List.of(chunk(9), chunk(8));

        List<CitedChunk> fused = RrfFuser.fuse(List.of(), keywordOnly, 60);

        assertThat(fused).extracting(CitedChunk::chunkId).containsExactly(9L, 8L);
        assertThat(fused.get(0).score()).isEqualTo(1.0 / 61, within(1e-9));
    }
}
