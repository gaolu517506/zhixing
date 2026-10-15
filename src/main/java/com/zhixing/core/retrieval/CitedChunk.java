package com.zhixing.core.retrieval;

/**
 * 检索结果单元（带引用溯源信息）。检索 SQL 的结果一律用本 record 承接，
 * 不引入入库线的 dao/entity（并行开发红线）。
 */
public record CitedChunk(long chunkId, long docId, String docName, String content, double score) {
}
