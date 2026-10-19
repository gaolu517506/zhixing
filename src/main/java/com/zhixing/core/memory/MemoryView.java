package com.zhixing.core.memory;

import java.util.List;

/**
 * 会话记忆视图（阶段 2 契约基线，详设 08-阶段2 §5）：滚动摘要 + 窗口内的完整轮次。
 * summary 可为 null（尚无摘要）；window 按时间升序，只含最近 window-size 轮。
 */
public record MemoryView(String summary, List<Turn> window) {
}
