package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 步骤流水（详设 13-阶段3 §3）：每轮 LLM 推理与每次工具执行各一条。
 * type：LLM / TOOL；status：SUCCESS / FAILED。
 */
@Data
@TableName("agent_run_step")
public class AgentRunStep {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long runId;

    private Integer iter;

    private String type;

    private String toolName;

    /** 截断 2000 落库。 */
    private String arguments;

    /** 截断 2000 落库。 */
    private String result;

    private Long costMs;

    private String status;

    private LocalDateTime createdAt;
}
