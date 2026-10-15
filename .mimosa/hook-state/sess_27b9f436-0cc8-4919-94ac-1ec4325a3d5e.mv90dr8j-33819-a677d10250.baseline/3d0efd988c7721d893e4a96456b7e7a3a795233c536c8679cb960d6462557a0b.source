package com.zhixing.dao.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 检索双通道 SQL（详设 §4.1）。全部走 #{} 预编译参数绑定，零字符串拼接 SQL——
 * 向量以 pgvector 文本字面量 "[0.1,0.2,...]" 绑定后 ::vector cast，无注入面。
 * 注意：SQL 是纯静态的，不用 &lt;script&gt; 包裹（动态标签才需要），
 * 因此 &lt;=&gt; 无需做 XML 转义；列清单用接口常量做编译期拼接。
 * kb_document 用 LEFT JOIN：检索行只强依赖 kb_chunk；doc 缺失（如 doc_id≤0 标记数据）
 * 不应让 chunk 从结果里消失，docName 为 null 由上层回退。
 */
@Mapper
public interface RetrievalQueryMapper {

    String COLUMNS = "c.id AS chunk_id, c.doc_id AS doc_id, d.file_name AS doc_name, c.content AS content, ";

    @Select("SELECT " + COLUMNS + "1 - (c.embedding <=> #{queryVector}::vector) AS score "
            + "FROM kb_chunk c "
            + "LEFT JOIN kb_document d ON d.id = c.doc_id "
            + "WHERE c.kb_id = #{kbId} "
            + "ORDER BY c.embedding <=> #{queryVector}::vector "
            + "LIMIT #{topK}")
    List<ChunkRow> vectorTopK(@Param("kbId") long kbId,
                              @Param("queryVector") String queryVector,
                              @Param("topK") int topK);

    /** pg_trgm 主通道：similarity 打分 + % 操作符（走 GIN trgm 索引，阈值取 pg_trgm.similarity_threshold 默认 0.3）。 */
    @Select("SELECT " + COLUMNS + "similarity(c.content, #{query}) AS score "
            + "FROM kb_chunk c "
            + "LEFT JOIN kb_document d ON d.id = c.doc_id "
            + "WHERE c.kb_id = #{kbId} AND c.content % #{query} "
            + "ORDER BY score DESC "
            + "LIMIT #{topK}")
    List<ChunkRow> trgmTopK(@Param("kbId") long kbId,
                            @Param("query") String query,
                            @Param("topK") int topK);

    /** ILIKE 保底通道（trgm 整句相似过严时的退化路径，详设 §4.1 注）；score 仍用 similarity 便于统一比较。 */
    @Select("SELECT " + COLUMNS + "similarity(c.content, #{query}) AS score "
            + "FROM kb_chunk c "
            + "LEFT JOIN kb_document d ON d.id = c.doc_id "
            + "WHERE c.kb_id = #{kbId} AND c.content ILIKE '%' || #{query} || '%' "
            + "ORDER BY score DESC "
            + "LIMIT #{topK}")
    List<ChunkRow> ilikeTopK(@Param("kbId") long kbId,
                             @Param("query") String query,
                             @Param("topK") int topK);
}
