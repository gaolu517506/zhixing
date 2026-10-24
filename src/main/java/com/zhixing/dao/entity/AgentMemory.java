package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 长期记忆（详设 14-v1.0 §3.2，V3 建表）：跨会话的用户级事实。
 * status：ACTIVE / SUPERSEDED / RETRACTED；SUPERSEDED 经 superseded_by 指向替代它的新记忆。
 */
@Data
@TableName("agent_memory")
public class AgentMemory {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 事实文本（一句一条）。 */
    private String content;

    private String status;

    /** SUPERSEDED 指向替代它的新记忆 id。 */
    private Long supersededBy;

    /** 产出该记忆的会话（溯源）。 */
    private Long sourceSessionId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
