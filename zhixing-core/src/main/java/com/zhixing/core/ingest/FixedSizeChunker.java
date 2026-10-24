package com.zhixing.core.ingest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 固定长度分块器（阶段 1 唯一实现，纯函数、无状态、可离线穷举测试）。
 *
 * 切分五规则（详设 §3.1 原文，单测固化）：
 * ① 先按换行把文本切成自然段；
 * ② 顺序累加段落，累加结果达到 chunk_size 即封块——语义取"先累加后封块"：段落永远完整，
 *    块长 = size ± 一段的长度（若强守 size 硬上限，大段落会把块压成远小于 size 的碎片）；
 * ③ 单段超过 chunk_size 时，在段内按 。？！ 切句后再累加（句单元不加重分隔符）；
 * ④ 封块时，下一块以当前块尾部 overlap 字符开头（检索答案被块边界切断时，邻块仍有完整上下文；
 *    代价是块长上限变为 size + overlap）；
 * ⑤ 丢弃纯空白块；收尾时仅剩 overlap 种子（与上一块尾部完全重复）的残余直接丢弃；
 *    末块不足 50 字符则并入前块。
 */
@Component
public class FixedSizeChunker implements Chunker {

    /** 规则⑤：末块并入前块的长度阈值 */
    static final int MERGE_THRESHOLD = 50;

    /** 规则③：句边界后向断言切分，保留标点在句尾 */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。？！])");

    /** 切分单元：paragraph 单元之间补回换行（还原原文结构），sentence 单元直接拼接（标点已在句尾） */
    private record Unit(String text, boolean paragraph) {
    }

    @Override
    public List<Chunk> chunk(String text, ChunkConfig cfg) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> blocks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (Unit unit : unitsOf(text, cfg.size())) {
            if (buffer.length() > 0 && unit.paragraph()) {
                buffer.append('\n');
            }
            buffer.append(unit.text());
            if (buffer.length() >= cfg.size()) {
                String sealed = buffer.toString();
                blocks.add(sealed);
                // 规则④：下一块以上一块尾部 overlap 字符开头
                buffer = new StringBuilder(tailOf(sealed, cfg.overlap()));
            }
        }
        if (buffer.length() > 0 && (blocks.isEmpty() || !blocks.get(blocks.size() - 1).endsWith(buffer.toString()))) {
            blocks.add(buffer.toString());
        }
        mergeTinyLastBlock(blocks);
        return indexed(blocks);
    }

    private List<Unit> unitsOf(String text, int size) {
        List<Unit> units = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            String paragraph = raw.trim();
            if (paragraph.isEmpty()) {
                continue;
            }
            if (paragraph.length() <= size) {
                units.add(new Unit(paragraph, true));
                continue;
            }
            // 规则③：单段超限，段内切句
            for (String rawSentence : SENTENCE_BOUNDARY.split(paragraph)) {
                String sentence = rawSentence.trim();
                if (!sentence.isEmpty()) {
                    units.add(new Unit(sentence, false));
                }
            }
        }
        return units;
    }

    /** 规则⑤：末块不足阈值并入前块（拼接还原该块原本缺失的换行结构）。 */
    private void mergeTinyLastBlock(List<String> blocks) {
        if (blocks.size() < 2) {
            return;
        }
        String last = blocks.get(blocks.size() - 1);
        if (last.length() < MERGE_THRESHOLD) {
            blocks.set(blocks.size() - 2, blocks.get(blocks.size() - 2) + '\n' + last);
            blocks.remove(blocks.size() - 1);
        }
    }

    private String tailOf(String s, int overlap) {
        return s.length() <= overlap ? s : s.substring(s.length() - overlap);
    }

    private List<Chunk> indexed(List<String> blocks) {
        List<Chunk> chunks = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            chunks.add(Chunk.of(i, blocks.get(i)));
        }
        return List.copyOf(chunks);
    }
}
