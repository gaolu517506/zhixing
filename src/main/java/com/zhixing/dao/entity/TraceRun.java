package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 链路追踪 Run（一次问答/入库/Agent 执行）。
 */
@Data
@TableName("trace_run")
public class TraceRun {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** QA / AGENT / INGEST。 */
    private String bizType;

    private Long bizId;

    /** RUNNING / COMPLETED / FAILED。 */
    private String status;

    private Long totalCostMs;

    private String errorMsg;

    private LocalDateTime createdAt;
}
