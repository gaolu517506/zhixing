package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 开放 API token（V4，F5，D16）。明文格式 zxk_&lt;random&gt; 仅发放时返回一次，
 * 库存 SHA-256；归属真实用户——请求经 TokenResolver 解析后以 owner 身份 +
 * API 渠道执行，与前端同一权限模型。日配额按 used_date 当天计数。
 */
@Data
@TableName("api_token")
public class ApiToken {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String name;

    private String tokenHash;

    private Long ownerUserId;

    /** ACTIVE / REVOKED */
    private String status;

    private Integer dailyQuota;

    private Integer usedToday;

    private LocalDate usedDate;

    private LocalDateTime expiresAt;

    private LocalDateTime lastUsedAt;

    private LocalDateTime createdAt;
}
