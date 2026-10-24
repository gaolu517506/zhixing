package com.zhixing.core.memory;

import java.util.List;

/**
 * 问题重写接口（阶段 2 契约基线补充）。QaService 依赖本接口；
 * 默认装配 {@link NoopQueryRewriter}（直通降级），T2-B 线的 LLM 实现以 @Primary 覆盖。
 */
public interface QueryRewriter {

    /**
     * @param history  窗口内历史轮次（升序；首轮为空列表）
     * @param summary  滚动摘要（可 null）
     * @param question 当前原始问题
     * @return rewritten=false 表示降级，调用方以 question 直查
     */
    RewrittenQuery rewrite(List<Turn> history, String summary, String question);
}
