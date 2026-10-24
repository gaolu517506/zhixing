package com.zhixing.core.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三指标契约测试（详设 §10.3 定义）：Hit@K 主指标 0/1、Recall@K 比例、MRR@K 排名倒数；全不命中一律 0。
 */
class EvalMetricsTest {

    private static final List<Long> RANKED = List.of(11L, 22L, 33L, 44L, 55L, 66L);

    @Test
    void hitAt5_前五含期望记1否则0() {
        assertThat(EvalMetrics.hitAtK(RANKED, Set.of(44L), 5)).isEqualTo(1.0);
        assertThat(EvalMetrics.hitAtK(RANKED, Set.of(66L), 5)).isEqualTo(0.0);
    }

    @Test
    void recallAt5_命中比例() {
        assertThat(EvalMetrics.recallAtK(RANKED, Set.of(22L, 66L), 5)).isEqualTo(0.5);
        assertThat(EvalMetrics.recallAtK(RANKED, Set.of(22L, 44L), 5)).isEqualTo(1.0);
    }

    @Test
    void mrrAt10_首个期望排名倒数() {
        assertThat(EvalMetrics.mrrAtK(RANKED, Set.of(22L), 10)).isEqualTo(0.5);
        assertThat(EvalMetrics.mrrAtK(RANKED, Set.of(11L), 10)).isEqualTo(1.0);
    }

    @Test
    void 边界_全不命中或空输入一律0不抛异常() {
        assertThat(EvalMetrics.mrrAtK(RANKED, Set.of(99L), 10)).isEqualTo(0.0);
        assertThat(EvalMetrics.hitAtK(List.of(), Set.of(11L), 5)).isEqualTo(0.0);
        assertThat(EvalMetrics.recallAtK(RANKED, Set.of(), 5)).isEqualTo(0.0);
        assertThat(EvalMetrics.hitAtK(null, null, 5)).isEqualTo(0.0);
    }
}
