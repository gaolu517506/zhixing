/**
 * 技术设施 · 向量检索通道：VectorStore 接口 → PgVectorStore（pgvector HNSW cosine）。
 *
 * <p>实现要点：查询向量以文本字面量 "[0.1,0.2,...]" 绑定后 ::vector cast（pgvector 无官方 JDBC 扩展），
 * LEFT JOIN kb_document 保 chunk 不因 doc 缺失消失。
 */
package com.zhixing.infra.vector;
