package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档。status 取值见 {@link com.zhixing.core.ingest.DocumentStatus}（DDL VARCHAR(16)，
 * 实体用 String 直映射，枚举语义在 core/ingest 层维护）。
 */
@Data
@TableName("kb_document")
public class KbDocument {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long kbId;

    private String fileName;

    /** 磁盘相对路径（相对存储根目录 zhixing.storage.local-root），如 "1948.../handbook.pdf" */
    private String filePath;

    /** SHA-256 hex，同库幂等判重 */
    private String fileHash;

    private String fileType;

    private Long fileSize;

    private String status;

    private Integer chunkCount;

    private String errorMsg;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
