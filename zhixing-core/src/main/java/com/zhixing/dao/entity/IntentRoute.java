package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zhixing.dao.handler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 意图路由规则（V7，F11，02-技术方案 §B10）。keywords/examples/target_kb_ids 为 JSONB
 * 数组，实体侧 String 承载（与 eval_run.config 同约定；DL-30 ⑧勘误——02 原设计数组列）。
 * ID 由服务层雪花赋值。
 */
@Data
@TableName(value = "intent_route", autoResultMap = true)
public class IntentRoute {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String name;

    /** JSONB 字符串：["保修","售后",...] */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String keywords;

    /** JSONB 字符串：few-shot 示例问句数组 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String examples;

    /** JSONB 字符串：目标库 ID 数组（应用层校验库存在；路由输出仍需过权限交集，D19） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String targetKbIds;

    private Boolean enabled;

    private LocalDateTime createdAt;
}
