package com.zhixing.service;

/**
 * 引用来源（SSE sources 事件与 qa_message.sources JSONB 的统一结构，概设 §3.4）。
 * snippet 为 content 前缀截断，前端只做展示预览。
 */
public record SourceRef(long chunkId, String docName, double score, String snippet) {
}
