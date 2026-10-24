package com.zhixing.core.memory;

import java.util.List;

/**
 * 问题重写产物（阶段 2 契约基线，详设 08-阶段2 §4）。
 * rewritten=false 表示降级（解析失败/超时/无历史），调用方以原始问题直查；
 * keywords 可为空列表（重写成功但未产出关键词时，关键词通道走整句）。
 */
public record RewrittenQuery(String standaloneQuestion, List<String> keywords, boolean rewritten) {
}
