package com.zhixing.infra.trace;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次链路执行的 trace 上下文：runId + 节点序号发生器（vector/keyword/fusion/rerank/llm 共用一个递增 seq）。
 * runId=0 表示 trace 关闭，所有埋点退化为直通。
 */
public record TraceContext(long runId, AtomicInteger seq) {

    public static TraceContext disabled() {
        return new TraceContext(0, new AtomicInteger());
    }

    public int nextSeq() {
        return seq.incrementAndGet();
    }
}
