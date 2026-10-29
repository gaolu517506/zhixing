package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhixing.dao.handler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评估运行（V5，F6，02-技术方案 §B6）。状态机 RUNNING → COMPLETED/FAILED；
 * config 记评估集来源与语料规模快照，report 记指标 + delta（同库上一个 COMPLETED 对比）。
 * config/report 为 JSONB，实体侧以 String 承载（与 agent_run 同约定）。
 */
@Data
@TableName(value = "eval_run", autoResultMap = true)
public class EvalRun {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long kbId;

    private Long createdBy;

    /** RUNNING / COMPLETED / FAILED */
    private String status;

    /** JSONB 字符串：{source, setPath?, setJsonChars?, setJsonSha256?, corpus:{docs,chunks}} */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String config;

    /** JSONB 字符串：{hitAt5, recallAt5, mrrAt10, layers, corpus, delta} */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String report;

    private String errorMsg;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;
}
