package com.zhixing.core.agent;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 取消协作（详设 13-阶段3 §9）：cancel 接口与 emitter onTimeout/onError 都落到本对象。
 * 标志位是协作检查点（每轮迭代入口 + 每工具执行前）的开关；Future.cancel(true) 负责打断
 * 阻塞中的 LLM 流读取（interrupt → OkHttp 抛 → 回调 onError）。
 */
public class AgentCancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private volatile Future<?> llmTask;

    /** 绑定当前轮的 LLM 流任务；若绑定前已取消，立即打断。 */
    public void bindLlmTask(Future<?> task) {
        this.llmTask = task;
        if (cancelled.get()) {
            task.cancel(true);
        }
    }

    public void cancel() {
        cancelled.set(true);
        Future<?> task = llmTask;
        if (task != null) {
            task.cancel(true);
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
