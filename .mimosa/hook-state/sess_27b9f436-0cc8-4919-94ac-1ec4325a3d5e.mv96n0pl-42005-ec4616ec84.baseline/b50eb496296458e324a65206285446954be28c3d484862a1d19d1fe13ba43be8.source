package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答会话。阶段 1 只做存取；summaryText/summarizedMsgId 阶段 2（会话摘要）启用。
 */
@Data
@TableName("qa_session")
public class QaSession {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 默认取问题前 20 字。 */
    private String title;

    private String summaryText;

    private Long summarizedMsgId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
