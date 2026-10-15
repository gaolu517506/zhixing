package com.zhixing.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhixing.dao.entity.KbChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /**
     * 批量插入分块，embedding 列用 ?::vector cast（pgvector 无 JDBC 扩展，向量当字符串传参，
     * 由 PG 端完成字面量 → vector 的解析）。调用方保证 list 非空（空 list 生成的 SQL 非法）。
     */
    @Insert("<script>"
            + "INSERT INTO kb_chunk (id, kb_id, doc_id, chunk_index, content, token_count, embedding) VALUES "
            + "<foreach collection='list' item='c' separator=','>"
            + "(#{c.id}, #{c.kbId}, #{c.docId}, #{c.chunkIndex}, #{c.content}, #{c.tokenCount}, #{c.embedding}::vector)"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("list") List<KbChunk> chunks);

    @Delete("DELETE FROM kb_chunk WHERE doc_id = #{docId}")
    int deleteByDocId(@Param("docId") Long docId);
}
