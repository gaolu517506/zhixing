package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库分块。
 *
 * 两处有意保留为字符串：
 * - embedding：pgvector 无 JDBC 扩展，vector 列在 Java 层当字符串（pgvector 文本格式 "[0.1,0.2,...]"），
 *   仅经 {@link com.zhixing.dao.mapper.KbChunkMapper#insertBatch} 以 ?::vector cast 写入，
 *   严禁走 BaseMapper 通用 insert（会把向量当 varchar 写坏）。
 * - metadata（JSONB）本线不读写，DDL 默认 '{}' 兜底，故不建字段。
 */
@Data
@TableName("kb_chunk")
public class KbChunk {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long kbId;

    private Long docId;

    /** 在文档内的序号，0 起 */
    private Integer chunkIndex;

    private String content;

    /** 估算 token 数，chars/1.6（决策 D8） */
    private Integer tokenCount;

    /** pgvector 文本字面量，仅配合 insertBatch 使用 */
    private String embedding;

    private LocalDateTime createdAt;

    /** float[] → pgvector 文本字面量。 */
    public static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
