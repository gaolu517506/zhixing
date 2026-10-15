package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 链路追踪 Node（run 内一个节点：vector_search / keyword_search / fusion / rerank / llm_chat...）。
 */
@Data
@TableName("trace_node")
public class TraceNode {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long runId;

    private String name;

    /** 同一 run 内递增。 */
    private Integer seq;

    private Long costMs;

    /** 截断到 zhixing.trace.max-text-length（默认 2000 字符）落库。 */
    private String input;

    private String output;

    /** SUCCESS / FAILED。 */
    private String status;

    private String error;

    private LocalDateTime createdAt;
}
