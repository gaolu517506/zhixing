package com.zhixing.core.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评估集解析与分层聚合的离线测试（fixture 自造 5 条，真实评估集等场景文档核对完成后才有）。
 */
class EvalSetSupportTest {

    @Test
    void 解析fixture_5条样本字段齐全() {
        List<EvalSetSupport.EvalCase> cases =
                EvalSetSupport.parse(Path.of("src/test/resources/eval/eval-set-fixture.jsonl"));

        assertThat(cases).hasSize(5);
        EvalSetSupport.EvalCase first = cases.get(0);
        assertThat(first.id()).isEqualTo("F001");
        assertThat(first.expectedDocIds()).containsExactly("01-param-xiaomi-15-ultra");
        assertThat(first.difficulty()).isEqualTo("easy");
    }

    @Test
    void 按难度分层且计数正确() {
        List<EvalSetSupport.CaseResult> results = EvalSetSupport.parse(
                        Path.of("src/test/resources/eval/eval-set-fixture.jsonl")).stream()
                .map(c -> new EvalSetSupport.CaseResult(c, List.of(1L), 1.0, 1.0, 1.0))
                .toList();

        var grouped = EvalSetSupport.groupByDifficulty(results);
        assertThat(grouped.get("easy")).hasSize(2);
        assertThat(grouped.get("medium")).hasSize(2);
        assertThat(grouped.get("hard")).hasSize(1);
        assertThat(EvalSetSupport.averageHitAt5(results)).isEqualTo(1.0);
    }
}
