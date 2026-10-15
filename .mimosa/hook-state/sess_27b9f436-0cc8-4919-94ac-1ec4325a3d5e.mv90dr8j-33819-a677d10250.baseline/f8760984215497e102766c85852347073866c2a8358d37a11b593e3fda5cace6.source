package com.zhixing.core.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chunker 四用例（详设 §8）+ 规则⑤两个边界。纯函数离线穷举——LLM 的流造不出来，
 * 但分块逻辑可以：所有断言只依赖输入文本与五规则。
 */
class FixedSizeChunkerTest {

    private final FixedSizeChunker chunker = new FixedSizeChunker();
    private final ChunkConfig cfg = new ChunkConfig(500, 50);

    /** 用例 1：空文本 / 纯空白 → 空列表（规则⑤丢弃空白块）。 */
    @Test
    void 用例1_空文本与纯空白返回空列表() {
        assertThat(chunker.chunk("", cfg)).isEmpty();
        assertThat(chunker.chunk("   ", cfg)).isEmpty();
        assertThat(chunker.chunk(" \n \n\t \n ", cfg)).isEmpty();
        assertThat(chunker.chunk(null, cfg)).isEmpty();
    }

    /** 用例 2：超长单段按句切分（规则③），相邻块携带 overlap（规则④），块尾必为句边界。 */
    @Test
    void 用例2_超长单段按句切分且相邻块携带重叠() {
        StringBuilder para = new StringBuilder();
        for (int i = 1; i <= 48; i++) {
            para.append("这是第").append(i).append("句话，用于测试超长单段的切分逻辑与重叠行为。");
        }
        List<Chunk> chunks = chunker.chunk(para.toString(), cfg);

        assertThat(chunks.size()).isGreaterThan(1);
        for (Chunk chunk : chunks) {
            // 块长上限 = size + overlap（规则④的代价），且尾部必停在句边界
            assertThat(chunk.content().length()).isLessThanOrEqualTo(cfg.size() + cfg.overlap());
            assertThat(chunk.content()).endsWith("。");
        }
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1).content();
            assertThat(chunks.get(i).content())
                    .startsWith(prev.substring(prev.length() - cfg.overlap()));
        }
        assertThat(chunks.get(0).content()).contains("这是第1句话");
        assertThat(chunks.get(chunks.size() - 1).content()).contains("48句话");
    }

    /** 用例 3：段落恰好整除——两段 250 累加到 501 封块，无第三块；块 2 = 50 字重叠 + 后两段。 */
    @Test
    void 用例3_段落恰好整除不产生残余块() {
        String p250 = "字".repeat(250);
        String text = String.join("\n", p250, p250, p250, p250);
        List<Chunk> chunks = chunker.chunk(text, cfg);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).hasSize(501);
        assertThat(chunks.get(1).content()).hasSize(552);   // 50 重叠 + 1 换行 + 250 + 1 换行 + 250
        assertThat(chunks.get(1).content()).startsWith(chunks.get(0).content().substring(501 - 50));
        assertThat(chunks.get(1).index()).isEqualTo(1);
    }

    /** 用例 3b：token 估算 chars/1.6（决策 D8）。 */
    @Test
    void 用例3b_tokenCount按字符数除以16估算() {
        String p250 = "字".repeat(250);
        List<Chunk> chunks = chunker.chunk(p250 + "\n" + p250, cfg);
        assertThat(chunks.get(0).content()).hasSize(501);
        assertThat(chunks.get(0).tokenCount()).isEqualTo((int) Math.round(501 / 1.6));
    }

    /** 用例 4：中文标点 。？！ 都能作句边界。 */
    @Test
    void 用例4_中文标点问叹句号均可作切句边界() {
        String[] endings = {"。", "？", "！"};
        StringBuilder para = new StringBuilder();
        for (int i = 1; i <= 80; i++) {
            para.append("第").append(i).append("句混合标点内容").append(endings[(i - 1) % 3]);
        }
        List<Chunk> chunks = chunker.chunk(para.toString(), cfg);

        assertThat(chunks.size()).isGreaterThan(1);
        for (Chunk chunk : chunks) {
            char last = chunk.content().charAt(chunk.content().length() - 1);
            assertThat(last).isIn('。', '？', '！');
        }
        String joined = String.join("", chunks.stream().map(Chunk::content).toList());
        assertThat(joined).contains("第1句混合标点内容。");
        assertThat(joined).contains("第2句混合标点内容？");
        assertThat(joined).contains("第3句混合标点内容！");
        assertThat(joined).contains("第80句混合标点内容？");   // (80-1)%3=1 → endings[1]="？"
    }

    /** 规则⑤：末块不足 50 字符并入前块；仅剩 overlap 种子的重复残余直接丢弃。 */
    @Test
    void 规则5_末块并入前块且纯重复残余被丢弃() {
        ChunkConfig small = new ChunkConfig(500, 10);
        String big = "a".repeat(550);
        String tiny = "b".repeat(30);

        List<Chunk> merged = chunker.chunk(big + "\n" + tiny, small);
        // 550 封块 → 残余 = 10 重叠 + 换行 + 30 = 41 < 50 → 并入前块
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).content()).contains("b".repeat(30));
        // 550 + 合并换行 1 + 重叠 10 + 残余换行 1 + 30 = 592
        assertThat(merged.get(0).content().length()).isEqualTo(592);

        List<Chunk> single = chunker.chunk("c".repeat(600), small);
        // 收尾 buffer 只剩 10 字符 overlap 种子（与块尾完全重复）→ 丢弃，不产生第二块
        assertThat(single).hasSize(1);
        assertThat(single.get(0).content()).hasSize(600);
    }
}
