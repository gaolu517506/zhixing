package com.zhixing.dao.mapper;

import lombok.Data;

/**
 * 检索 SQL 的行投影（可变 POJO，供 MyBatis 反射映射），由 store 层转成 CitedChunk record。
 * 之所以不直接映射 record：MyBatis 对无 setter 的 record 需要构造器映射配置，得不偿失。
 */
@Data
public class ChunkRow {

    private Long chunkId;

    private Long docId;

    /** LEFT JOIN kb_document 可能无行（如检索验证用的 doc_id≤0 标记数据），此时为 null。 */
    private String docName;

    private String content;

    private Double score;
}
