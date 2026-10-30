package com.zhixing.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组织成员（02 §B9）：复合主键 (org_id, user_id)；role：ADMIN=组织管理员（管理本组织
 * 成员与库归属）/ MEMBER=普通成员（可见本组织库）。org 归属只扩大可见范围，不授库写权限。
 */
@Data
@TableName("org_member")
public class OrgMember {

    private Long orgId;

    private Long userId;

    private String role;

    private LocalDateTime createdAt;
}
