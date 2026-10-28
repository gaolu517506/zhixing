package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhixing.dao.handler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计事件（V4，F3，D15）。仅追加：dao 层不提供 update/delete，
 * 事件枚举见 V4 注释（LOGIN…EVAL_RUN）。detail 为 JSONB，实体侧以 String 承载
 * （与 agent_run.messages_snapshot 同约定）。
 */
@Data
@TableName(value = "audit_event", autoResultMap = true)
public class AuditEvent {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long actorId;

    /** 冗余用户名，防改名后审计漂移；系统动作（如首启）可为 null */
    private String actorName;

    private String action;

    private String targetType;

    private String targetId;

    /** OK / FAIL */
    private String result;

    /** JSONB 字符串（可空） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String detail;

    private LocalDateTime createdAt;
}
