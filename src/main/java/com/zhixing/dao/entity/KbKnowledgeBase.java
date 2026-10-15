package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库。ID 由服务层用 hutool 雪花赋值（DDL 无自增，IdType.INPUT 即"调用方给 ID"）；
 * chunk_size / chunk_overlap 不在此赋值——插入时置 null 由 DDL 默认值（500/50）填充，默认值只声明一处。
 */
@Data
@TableName("kb_knowledge_base")
public class KbKnowledgeBase {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String name;

    private String description;

    private Integer chunkSize;

    private Integer chunkOverlap;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
