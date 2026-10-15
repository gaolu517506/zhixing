package com.zhixing.core.ingest;

import java.util.List;

/**
 * 文档解析产物。pages 为各页文本（可空）：阶段 1 的 Tika 全文解析不保页序，
 * 字段留给日后按页解析的解析器（metadata 富化用）。
 */
public record ParsedDocument(String text, List<Integer> pages) {

    public static ParsedDocument of(String text) {
        return new ParsedDocument(text, null);
    }
}
