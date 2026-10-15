package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答消息。sources 为 JSONB 列，这里以 JSON 字符串承载（List&lt;SourceRef&gt; ↔ JSON 由
 * QaService 用 ObjectMapper 互转）——不用 MP JacksonTypeHandler 的原因：字段是
 * List&lt;SourceRef&gt; 泛型集合，JacksonTypeHandler 经 MP 反射拿到的是原始 List，
 * 读回会变成 List&lt;LinkedHashMap&gt；字符串方案读写都显式可控，且 PG JDBC 对
 * jsonb 列接受未类型化字符串参数，无需 ::jsonb cast。
 */
@Data
@TableName("qa_message")
public class QaMessage {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long sessionId;

    /** user / assistant。 */
    private String role;

    private String content;

    /** workflow / agent / ... */
    private String engine;

    /** assistant 专属：引用来源数组 JSON（SourceRef 结构），user 消息为 null。 */
    private String sources;

    private Integer promptTokens;

    private Integer completionTokens;

    private Long costMs;

    private LocalDateTime createdAt;
}
